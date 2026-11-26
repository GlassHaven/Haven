//! MS-RDPEFS (`rdpdr` static channel) folder redirection over Android SAF (#890).
//!
//! Protocol validation, Windows path containment, DOS wildcard matching, handle
//! lifecycles (`FILE_DELETE_ON_CLOSE`, `FileDispositionInformation`), and
//! byte-range locks live here in Rust; tree-relative storage operations cross
//! UniFFI via [`crate::DriveCallback`] so Android's `DocumentsContract` /
//! `ContentResolver` serves the user-selected SAF tree without new manifest
//! permissions.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use ironrdp_core::impl_as_any;
use ironrdp_pdu::{encode_err, PduResult};
use ironrdp_rdpdr::pdu::efs::{
    Boolean, ClientDriveLockControlResponse, ClientDriveQueryDirectoryResponse,
    ClientDriveQueryInformationResponse, ClientDriveQuerySecurityResponse,
    ClientDriveQueryVolumeInformationResponse, ClientDriveSetInformationResponse,
    ClientDriveSetSecurityResponse, CreateDisposition, CreateOptions, DesiredAccess,
    DeviceCloseResponse, DeviceControlRequest, DeviceControlResponse, DeviceCreateRequest,
    DeviceCreateResponse, DeviceFlushBuffersResponse, DeviceIoRequest, DeviceIoResponse, DeviceReadRequest, DeviceReadResponse, DeviceWriteRequest,
    DeviceWriteResponse, FileAttributeTagInformation, FileAttributes, FileBasicInformation,
    FileBothDirectoryInformation, FileDirectoryInformation, FileFsAttributeInformation,
    FileFsFullSizeInformation, FileFsSizeInformation, FileFsVolumeInformation,
    FileFullDirectoryInformation, FileInformationClass, FileInformationClassLevel,
    FileNamesInformation, FileStandardInformation, FileSystemAttributes,
    FileSystemInformationClass, FileSystemInformationClassLevel, Information, LockOperation,
    NtStatus, RdpLockInfo, ServerDeviceAnnounceResponse, ServerDriveIoRequest,
    ServerDriveLockControlRequest, ServerDriveQueryDirectoryRequest,
    ServerDriveQueryInformationRequest, ServerDriveQueryVolumeInformationRequest,
    ServerDriveSetInformationRequest,
};
use ironrdp_rdpdr::pdu::esc::{ScardCall, ScardIoCtlCode};
use ironrdp_rdpdr::pdu::RdpdrPdu;
use ironrdp_rdpdr::{Rdpdr, RdpdrBackend};
use ironrdp_svc::SvcMessage;
use log::{debug, info, warn};

use crate::{DriveCallback, RdpDriveEntry, RdpDriveStatus};

/// Device ID announced for the shared SAF folder.
const SAF_DRIVE_DEVICE_ID: u32 = 1;

/// Computer name displayed in Windows File Explorer (`"<folder> on Haven"`).
const CLIENT_COMPUTER_NAME: &str = "Haven";

/// Upper bound on simultaneously open RDPDR file handles.
const MAX_OPEN_FILES: usize = 256;

/// Largest single `DeviceReadRequest` allocation permitted (`1 MiB`).
const MAX_READ_LENGTH: u32 = 1024 * 1024;

/// Longest component name Windows accepts, in UTF-16 code units.
const MAX_NAME_CODE_UNITS: usize = 255;

/// Nominal reported volume capacity (`64 GiB` in `4 KiB` allocation units) for SAF trees,
/// where Android `DocumentsContract` does not expose underlying block-device `statvfs`.
const NOMINAL_TOTAL_UNITS: i64 = 16 * 1024 * 1024;
const NOMINAL_FREE_UNITS: i64 = 8 * 1024 * 1024;
const BYTES_PER_SECTOR: u32 = 512;
const SECTORS_PER_UNIT: u32 = 8;

/// Windows Epoch offset (`1601-01-01` -> `1970-01-01`) in 100-ns ticks.
const WINDOWS_EPOCH_100NS: i64 = 116_444_736_000_000_000;

fn status_cannot_delete() -> NtStatus {
    NtStatus::from(0xC000_0121)
}

fn status_file_lock_conflict() -> NtStatus {
    NtStatus::from(0xC000_0054)
}

fn status_range_not_locked() -> NtStatus {
    NtStatus::from(0xC000_007E)
}

fn status_invalid_lock_range() -> NtStatus {
    NtStatus::from(0xC000_001E)
}

fn map_drive_status(status: RdpDriveStatus) -> NtStatus {
    match status {
        RdpDriveStatus::Ok => NtStatus::SUCCESS,
        RdpDriveStatus::NotFound => NtStatus::NO_SUCH_FILE,
        RdpDriveStatus::PathNotFound => NtStatus::OBJECT_PATH_NOT_FOUND,
        RdpDriveStatus::AlreadyExists => NtStatus::OBJECT_NAME_COLLISION,
        RdpDriveStatus::NotADirectory => NtStatus::NOT_A_DIRECTORY,
        RdpDriveStatus::IsADirectory => NtStatus::FILE_IS_A_DIRECTORY,
        RdpDriveStatus::DirectoryNotEmpty => NtStatus::DIRECTORY_NOT_EMPTY,
        RdpDriveStatus::AccessDenied => NtStatus::ACCESS_DENIED,
        RdpDriveStatus::WriteProtected => NtStatus::MEDIA_WRITE_PROTECTED,
        RdpDriveStatus::DiskFull => NtStatus::DISK_FULL,
        RdpDriveStatus::InvalidName => NtStatus::OBJECT_NAME_INVALID,
        RdpDriveStatus::IoError => NtStatus::UNSUCCESSFUL,
    }
}

/// Sanitize a user-facing folder label into a valid NUL-free RDPDR share name.
pub(crate) fn sanitize_share_name(raw: &str) -> String {
    let cleaned: String = raw
        .chars()
        .filter(|&c| {
            c > '\u{1f}' && !matches!(c, '\0' | '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*')
        })
        .take(64)
        .collect();
    let trimmed = cleaned.trim().trim_end_matches([' ', '.']);
    if trimmed.is_empty() {
        "Haven".to_owned()
    } else {
        trimmed.to_owned()
    }
}

pub(crate) fn new_static_client(state: Arc<RwLock<crate::SessionState>>, share_name: &str) -> Rdpdr {
    let name = sanitize_share_name(share_name);
    Rdpdr::new(
        Box::new(HavenRdpdrBackend::new(state)),
        CLIENT_COMPUTER_NAME.to_owned(),
    )
    .with_drives(Some(vec![(SAF_DRIVE_DEVICE_ID, name)]))
}

/// Convert Unix epoch milliseconds to 100-ns Windows FILETIME ticks.
pub(crate) fn millis_to_filetime(ms: i64) -> i64 {
    if ms <= 0 {
        return WINDOWS_EPOCH_100NS;
    }
    ms.saturating_mul(10_000).saturating_add(WINDOWS_EPOCH_100NS)
}

fn file_attributes_for(entry: &RdpDriveEntry) -> FileAttributes {
    let mut attrs = FileAttributes::empty();
    if entry.is_dir {
        attrs |= FileAttributes::FILE_ATTRIBUTE_DIRECTORY;
    } else {
        attrs |= FileAttributes::FILE_ATTRIBUTE_ARCHIVE;
    }
    if entry.name.len() > 1 && entry.name.starts_with('.') && entry.name.as_bytes()[1] != b'.' {
        attrs |= FileAttributes::FILE_ATTRIBUTE_HIDDEN;
    }
    if entry.read_only {
        attrs |= FileAttributes::FILE_ATTRIBUTE_READONLY;
    }
    attrs
}

/// Validated tree-relative path (`"/"` or `"/a/b"`), guaranteed free of `..`,
/// `.`, backslashes, NULs, control characters, and trailing spaces/dots.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub(crate) struct SafPath(String);

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct InvalidSafPath;

impl SafPath {
    pub(crate) fn parse(raw: &str) -> Result<Self, InvalidSafPath> {
        let trimmed = raw.strip_prefix('\\').unwrap_or(raw);
        Self::from_backslash_components(trimmed)
    }

    pub(crate) fn parse_query(raw: &str) -> Result<(Self, &str), InvalidSafPath> {
        let trimmed = raw.strip_prefix('\\').unwrap_or(raw);
        let (dir_part, pattern) = if trimmed.is_empty() {
            ("", "*")
        } else {
            trimmed.rsplit_once('\\').unwrap_or(("", trimmed))
        };
        let valid_pattern = !matches!(pattern, "" | "." | "..")
            && pattern.encode_utf16().count() <= MAX_NAME_CODE_UNITS
            && !pattern
                .chars()
                .any(|c| c <= '\u{1f}' || matches!(c, ':' | '/' | '|'));
        if !valid_pattern {
            return Err(InvalidSafPath);
        }
        Ok((Self::from_backslash_components(dir_part)?, pattern))
    }

    fn from_backslash_components(s: &str) -> Result<Self, InvalidSafPath> {
        if s.is_empty() {
            return Ok(Self("/".to_owned()));
        }
        let mut out = String::with_capacity(s.len() + 1);
        for comp in s.split('\\') {
            if !is_valid_component(comp) {
                return Err(InvalidSafPath);
            }
            out.push('/');
            out.push_str(comp);
        }
        Ok(Self(out))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }

    pub(crate) fn is_root(&self) -> bool {
        self.0 == "/"
    }

    pub(crate) fn parent(&self) -> Option<Self> {
        if self.is_root() {
            None
        } else {
            let (parent, _) = self.0.rsplit_once('/')?;
            if parent.is_empty() {
                Some(Self("/".to_owned()))
            } else {
                Some(Self(parent.to_owned()))
            }
        }
    }

    pub(crate) fn join_literal(&self, child: &str) -> Result<Self, InvalidSafPath> {
        if !is_valid_component(child) {
            return Err(InvalidSafPath);
        }
        if self.is_root() {
            Ok(Self(format!("/{child}")))
        } else {
            Ok(Self(format!("{}/{child}", self.0)))
        }
    }

    pub(crate) fn is_strict_prefix_of(&self, other: &Self) -> bool {
        if self.is_root() {
            !other.is_root()
        } else {
            other
                .0
                .strip_prefix(&self.0)
                .is_some_and(|rest| rest.starts_with('/'))
        }
    }
}

fn is_valid_component(name: &str) -> bool {
    !matches!(name, "" | "." | "..")
        && name.encode_utf16().count() <= MAX_NAME_CODE_UNITS
        && !name.ends_with([' ', '.'])
        && !name.chars().any(|c| {
            c <= '\u{1f}' || matches!(c, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*')
        })
}

pub(crate) fn contains_wildcards(component: &str) -> bool {
    component.contains(['*', '?', '<', '>', '"'])
}

/// MS-FSA 2.1.4.4 case-insensitive DOS wildcard matcher (`*`, `?`, `<`, `>`, `"`).
pub(crate) fn is_name_in_expression(name: &str, expression: &str) -> bool {
    if name.is_empty() || expression.is_empty() {
        return name.is_empty() && expression.is_empty();
    }
    if matches!(expression, "*" | "*.*") {
        return true;
    }

    let name: Vec<char> = name.chars().flat_map(char::to_lowercase).collect();
    let expression: Vec<char> = expression.chars().flat_map(char::to_lowercase).collect();
    let last_dot = name.iter().rposition(|&c| c == '.');

    let mut rest = vec![false; name.len() + 1];
    rest[name.len()] = true;
    let mut matched = vec![false; name.len() + 1];
    for wildcard in expression.iter().rev() {
        for i in (0..=name.len()).rev() {
            let ch = name.get(i).copied();
            matched[i] = match wildcard {
                '*' => rest[i] || (ch.is_some() && matched[i + 1]),
                '?' => ch.is_some() && rest[i + 1],
                '<' => rest[i] || (ch.is_some() && last_dot != Some(i) && matched[i + 1]),
                '>' => match ch {
                    Some(c) if c != '.' => rest[i + 1],
                    _ => rest[i],
                },
                '"' => match ch {
                    Some('.') => rest[i + 1],
                    Some(_) => false,
                    None => rest[i],
                },
                literal => ch == Some(*literal) && rest[i + 1],
            };
        }
        std::mem::swap(&mut rest, &mut matched);
    }
    rest[0]
}

#[derive(Debug)]
struct OpenHandle {
    path: SafPath,
    is_dir: bool,
    may_write: bool,
    may_delete: bool,
    delete_on_close: bool,
    listing: Option<Vec<RdpDriveEntry>>,
    listing_cursor: usize,
}

const MAX_LOCKS: usize = 4_096;
const MAX_RANGES: usize = 64;

#[derive(Debug, Default)]
struct ByteRangeLocks {
    locks: Vec<ByteRangeLock>,
}

#[derive(Debug)]
struct ByteRangeLock {
    path: SafPath,
    owner: u32,
    offset: u64,
    length: u64,
    exclusive: bool,
}

impl ByteRangeLocks {
    fn conflicts(
        &self,
        path: &SafPath,
        owner: u32,
        offset: u64,
        length: u64,
        exclusive: bool,
        lock_intent: bool,
    ) -> bool {
        if offset == 0 && length == 0 {
            return false;
        }
        let last = last_byte(offset, length);
        self.locks
            .iter()
            .filter(|l| &l.path == path)
            .any(|l| {
                let overlaps = !(l.offset == 0 && l.length == 0)
                    && offset <= last_byte(l.offset, l.length)
                    && l.offset <= last;
                if !overlaps {
                    return false;
                }
                if l.exclusive {
                    l.owner != owner || (exclusive && lock_intent)
                } else {
                    exclusive
                }
            })
    }

    fn lock(
        &mut self,
        owner: u32,
        path: &SafPath,
        ranges: &[RdpLockInfo],
        exclusive: bool,
    ) -> NtStatus {
        if let Err(status) = validate_ranges(ranges) {
            return status;
        }
        let first_added = self.locks.len();
        for range in ranges {
            if self.conflicts(path, owner, range.offset, range.length, exclusive, true) {
                self.locks.truncate(first_added);
                return NtStatus::LOCK_NOT_GRANTED;
            }
            if self.locks.len() >= MAX_LOCKS {
                self.locks.truncate(first_added);
                return NtStatus::INSUFFICIENT_RESOURCES;
            }
            self.locks.push(ByteRangeLock {
                path: path.clone(),
                owner,
                offset: range.offset,
                length: range.length,
                exclusive,
            });
        }
        NtStatus::SUCCESS
    }

    fn unlock(&mut self, owner: u32, path: &SafPath, ranges: &[RdpLockInfo]) -> NtStatus {
        if let Err(status) = validate_ranges(ranges) {
            return status;
        }
        for range in ranges {
            let matching = |l: &ByteRangeLock| {
                &l.path == path
                    && l.owner == owner
                    && l.offset == range.offset
                    && l.length == range.length
            };
            let idx = self
                .locks
                .iter()
                .position(|l| matching(l) && l.exclusive)
                .or_else(|| self.locks.iter().rposition(matching));
            let Some(idx) = idx else {
                return status_range_not_locked();
            };
            self.locks.swap_remove(idx);
        }
        NtStatus::SUCCESS
    }

    fn close(&mut self, owner: u32) {
        self.locks.retain(|l| l.owner != owner);
    }

    fn rename_path(&mut self, from: &SafPath, to: &SafPath) {
        for lock in &mut self.locks {
            if &lock.path == from {
                lock.path = to.clone();
            }
        }
    }
}

fn validate_ranges(ranges: &[RdpLockInfo]) -> Result<(), NtStatus> {
    if ranges.is_empty() {
        return Err(NtStatus::INVALID_PARAMETER);
    }
    if ranges.len() > MAX_RANGES {
        return Err(NtStatus::INSUFFICIENT_RESOURCES);
    }
    if ranges
        .iter()
        .any(|r| r.length != 0 && r.offset.checked_add(r.length - 1).is_none())
    {
        return Err(status_invalid_lock_range());
    }
    Ok(())
}

fn last_byte(offset: u64, length: u64) -> u64 {
    match length.checked_sub(1) {
        Some(extent) => offset.saturating_add(extent),
        None => offset.wrapping_sub(1),
    }
}

pub(crate) struct HavenRdpdrBackend {
    state: Arc<RwLock<crate::SessionState>>,
    next_id: u32,
    handles: HashMap<u32, OpenHandle>,
    pending_deletes: HashMap<SafPath, bool>,
    locks: ByteRangeLocks,
}

impl core::fmt::Debug for HavenRdpdrBackend {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("HavenRdpdrBackend")
            .field("open_handles", &self.handles.len())
            .field("pending_deletes", &self.pending_deletes.len())
            .finish()
    }
}

impl_as_any!(HavenRdpdrBackend);

impl HavenRdpdrBackend {
    pub(crate) fn new(state: Arc<RwLock<crate::SessionState>>) -> Self {
        Self {
            state,
            next_id: 1,
            handles: HashMap::new(),
            pending_deletes: HashMap::new(),
            locks: ByteRangeLocks::default(),
        }
    }

    fn callback(&self) -> Option<Arc<dyn DriveCallback>> {
        self.state.read().ok().and_then(|s| s.drive_callback.clone())
    }

    fn alloc_file_id(&mut self) -> u32 {
        loop {
            let id = self.next_id;
            self.next_id = self.next_id.wrapping_add(1);
            if id != 0 && !self.handles.contains_key(&id) {
                return id;
            }
        }
    }

    fn has_delete_pending_on_or_above(&self, path: &SafPath) -> bool {
        self.pending_deletes
            .keys()
            .any(|marked| marked == path || marked.is_strict_prefix_of(path))
    }

    fn close_handle(&mut self, file_id: u32) {
        self.locks.close(file_id);
        let Some(handle) = self.handles.remove(&file_id) else {
            return;
        };
        let Some(cb) = self.callback() else {
            return;
        };
        if handle.delete_on_close && !handle.path.is_root() {
            let can_mark = if handle.is_dir {
                let listed = cb.list_dir(handle.path.as_str().to_owned());
                listed.status == RdpDriveStatus::Ok && listed.entries.is_empty()
            } else {
                true
            };
            if can_mark {
                self.pending_deletes
                    .insert(handle.path.clone(), handle.is_dir);
            }
        }
        let still_open = self.handles.values().any(|h| h.path == handle.path);
        if !still_open {
            cb.close_path(handle.path.as_str().to_owned());
            if let Some(is_dir) = self.pending_deletes.remove(&handle.path) {
                let status = cb.delete_path(handle.path.as_str().to_owned(), is_dir);
                if status != RdpDriveStatus::Ok {
                    debug!("RDPDR delete-on-close returned {:?}", status);
                }
            }
        }
    }

    fn close_all_handles(&mut self) {
        let ids: Vec<u32> = self.handles.keys().copied().collect();
        for id in ids {
            self.close_handle(id);
        }
    }

    fn handle_create(&mut self, req: DeviceCreateRequest) -> PduResult<Vec<SvcMessage>> {
        let file_id = self.alloc_file_id();
        let Some(cb) = self.callback() else {
            return create_error_resp(req.device_io_request, NtStatus::ACCESS_DENIED);
        };
        if self.handles.len() >= MAX_OPEN_FILES {
            warn!("RDPDR rejected create beyond MAX_OPEN_FILES ({MAX_OPEN_FILES})");
            return create_error_resp(req.device_io_request, NtStatus::INSUFFICIENT_RESOURCES);
        }

        let Ok(path) = SafPath::parse(&req.path) else {
            return create_error_resp(req.device_io_request, NtStatus::OBJECT_NAME_INVALID);
        };

        if !matches!(
            req.create_disposition,
            CreateDisposition::FILE_SUPERSEDE
                | CreateDisposition::FILE_OPEN
                | CreateDisposition::FILE_CREATE
                | CreateDisposition::FILE_OPEN_IF
                | CreateDisposition::FILE_OVERWRITE
                | CreateDisposition::FILE_OVERWRITE_IF
        ) {
            return create_error_resp(req.device_io_request, NtStatus::INVALID_PARAMETER);
        }

        let want_dir = req.create_options.contains(CreateOptions::FILE_DIRECTORY_FILE);
        let want_file = req
            .create_options
            .contains(CreateOptions::FILE_NON_DIRECTORY_FILE);
        if want_dir
            && (want_file
                || !matches!(
                    req.create_disposition,
                    CreateDisposition::FILE_CREATE
                        | CreateDisposition::FILE_OPEN
                        | CreateDisposition::FILE_OPEN_IF
                ))
        {
            return create_error_resp(req.device_io_request, NtStatus::INVALID_PARAMETER);
        }

        let delete_on_close = req
            .create_options
            .contains(CreateOptions::FILE_DELETE_ON_CLOSE);
        if delete_on_close && !req.desired_access.contains(DesiredAccess::DELETE) {
            return create_error_resp(req.device_io_request, NtStatus::INVALID_PARAMETER);
        }
        if delete_on_close && path.is_root() {
            return create_error_resp(req.device_io_request, NtStatus::ACCESS_DENIED);
        }

        if self.has_delete_pending_on_or_above(&path) {
            return create_error_resp(req.device_io_request, NtStatus::DELETE_PENDING);
        }

        // Verify parent directory exists when not opening the root itself.
        if let Some(parent) = path.parent() {
            match cb.stat(parent.as_str().to_owned()) {
                Some(entry) if entry.is_dir => {}
                Some(_) => {
                    return create_error_resp(req.device_io_request, NtStatus::OBJECT_PATH_NOT_FOUND)
                }
                None => {
                    return create_error_resp(req.device_io_request, NtStatus::OBJECT_PATH_NOT_FOUND)
                }
            }
        }

        let truncates = matches!(
            req.create_disposition,
            CreateDisposition::FILE_SUPERSEDE
                | CreateDisposition::FILE_OVERWRITE
                | CreateDisposition::FILE_OVERWRITE_IF
        );
        let all_access = DesiredAccess::GENERIC_ALL | DesiredAccess::MAXIMUM_ALLOWED;
        let wants_write = truncates
            || req.desired_access.intersects(
                DesiredAccess::FILE_WRITE_DATA_OR_FILE_ADD_FILE
                    | DesiredAccess::FILE_APPEND_DATA_OR_FILE_ADD_SUBDIRECTORY
                    | DesiredAccess::GENERIC_WRITE
                    | all_access.clone(),
            );
        let may_delete = req
            .desired_access
            .intersects(DesiredAccess::DELETE | all_access);

        let existing = if path.is_root() {
            Some(RdpDriveEntry {
                name: "/".to_owned(),
                is_dir: true,
                size: 0,
                modified_ms: 0,
                read_only: false,
            })
        } else {
            cb.stat(path.as_str().to_owned())
        };

        let (is_dir, information, may_write) = match existing {
            Some(entry) if entry.is_dir => {
                if want_file {
                    return create_error_resp(req.device_io_request, NtStatus::FILE_IS_A_DIRECTORY);
                }
                if !matches!(
                    req.create_disposition,
                    CreateDisposition::FILE_OPEN | CreateDisposition::FILE_OPEN_IF
                ) {
                    let status = if path.is_root() {
                        NtStatus::ACCESS_DENIED
                    } else {
                        NtStatus::OBJECT_NAME_COLLISION
                    };
                    return create_error_resp(req.device_io_request, status);
                }
                if delete_on_close && entry.read_only {
                    return create_error_resp(req.device_io_request, status_cannot_delete());
                }
                (true, Information::FILE_OPENED, false)
            }
            Some(entry) => {
                if want_dir {
                    let status = if req.create_disposition == CreateDisposition::FILE_CREATE {
                        NtStatus::OBJECT_NAME_COLLISION
                    } else {
                        NtStatus::NOT_A_DIRECTORY
                    };
                    return create_error_resp(req.device_io_request, status);
                }
                if req.create_disposition == CreateDisposition::FILE_CREATE {
                    return create_error_resp(req.device_io_request, NtStatus::OBJECT_NAME_COLLISION);
                }
                if delete_on_close && entry.read_only {
                    return create_error_resp(req.device_io_request, status_cannot_delete());
                }
                let effective_write = if entry.read_only {
                    if truncates
                        || (wants_write
                            && !req.desired_access.contains(DesiredAccess::MAXIMUM_ALLOWED))
                    {
                        return create_error_resp(req.device_io_request, NtStatus::ACCESS_DENIED);
                    }
                    false
                } else {
                    wants_write
                };
                if truncates {
                    let st = cb.truncate(path.as_str().to_owned(), 0);
                    if st != RdpDriveStatus::Ok {
                        return create_error_resp(req.device_io_request, map_drive_status(st));
                    }
                }
                let info = match req.create_disposition {
                    CreateDisposition::FILE_SUPERSEDE => Information::FILE_SUPERSEDED,
                    CreateDisposition::FILE_OVERWRITE | CreateDisposition::FILE_OVERWRITE_IF => {
                        Information::FILE_OVERWRITTEN
                    }
                    _ => Information::FILE_OPENED,
                };
                (false, info, effective_write)
            }
            None if want_dir => {
                if req.create_disposition == CreateDisposition::FILE_OPEN {
                    return create_error_resp(req.device_io_request, NtStatus::NO_SUCH_FILE);
                }
                let st = cb.mkdir(path.as_str().to_owned());
                if st != RdpDriveStatus::Ok {
                    return create_error_resp(req.device_io_request, map_drive_status(st));
                }
                (true, Information::FILE_SUPERSEDED, false)
            }
            None => {
                if matches!(
                    req.create_disposition,
                    CreateDisposition::FILE_OPEN | CreateDisposition::FILE_OVERWRITE
                ) {
                    return create_error_resp(req.device_io_request, NtStatus::NO_SUCH_FILE);
                }
                let st = cb.create_file(path.as_str().to_owned(), true);
                if st != RdpDriveStatus::Ok {
                    return create_error_resp(req.device_io_request, map_drive_status(st));
                }
                (false, Information::FILE_SUPERSEDED, true)
            }
        };

        self.handles.insert(
            file_id,
            OpenHandle {
                path,
                is_dir,
                may_write,
                may_delete,
                delete_on_close,
                listing: None,
                listing_cursor: 0,
            },
        );

        Ok(vec![SvcMessage::from(RdpdrPdu::DeviceCreateResponse(
            DeviceCreateResponse {
                device_io_reply: DeviceIoResponse::new(req.device_io_request, NtStatus::SUCCESS),
                file_id,
                information,
            },
        ))])
    }

    fn handle_read(&mut self, req: DeviceReadRequest) -> PduResult<Vec<SvcMessage>> {
        let file_id = req.device_io_request.file_id;
        let Some(cb) = self.callback() else {
            return read_resp(req.device_io_request, NtStatus::ACCESS_DENIED, Vec::new());
        };
        let Some(handle) = self.handles.get(&file_id) else {
            return read_resp(req.device_io_request, NtStatus::NO_SUCH_FILE, Vec::new());
        };
        if handle.is_dir {
            return read_resp(req.device_io_request, NtStatus::INVALID_DEVICE_REQUEST, Vec::new());
        }
        if req.length > MAX_READ_LENGTH {
            warn!("RDPDR rejected oversized read ({} bytes)", req.length);
            return read_resp(req.device_io_request, NtStatus::INVALID_PARAMETER, Vec::new());
        }
        if req.length == 0 {
            return read_resp(req.device_io_request, NtStatus::SUCCESS, Vec::new());
        }
        if self.locks.conflicts(
            &handle.path,
            file_id,
            req.offset,
            u64::from(req.length),
            false,
            false,
        ) {
            return read_resp(
                req.device_io_request,
                status_file_lock_conflict(),
                Vec::new(),
            );
        }
        let result = cb.read_at(handle.path.as_str().to_owned(), req.offset, req.length);
        if result.status != RdpDriveStatus::Ok {
            return read_resp(
                req.device_io_request,
                map_drive_status(result.status),
                Vec::new(),
            );
        }
        read_resp(req.device_io_request, NtStatus::SUCCESS, result.data)
    }

    fn handle_write(&mut self, req: DeviceWriteRequest) -> PduResult<Vec<SvcMessage>> {
        let file_id = req.device_io_request.file_id;
        let Some(cb) = self.callback() else {
            return write_resp(req.device_io_request, NtStatus::ACCESS_DENIED, 0);
        };
        let Some(handle) = self.handles.get(&file_id) else {
            return write_resp(req.device_io_request, NtStatus::NO_SUCH_FILE, 0);
        };
        if handle.is_dir || !handle.may_write {
            return write_resp(req.device_io_request, NtStatus::ACCESS_DENIED, 0);
        }
        let write_len = u64::try_from(req.write_data.len()).unwrap_or(u64::MAX);
        if write_len == 0 {
            return write_resp(req.device_io_request, NtStatus::SUCCESS, 0);
        }
        if self
            .locks
            .conflicts(&handle.path, file_id, req.offset, write_len, true, false)
        {
            return write_resp(req.device_io_request, status_file_lock_conflict(), 0);
        }
        let result = cb.write_at(handle.path.as_str().to_owned(), req.offset, req.write_data);
        if result.status != RdpDriveStatus::Ok {
            return write_resp(req.device_io_request, map_drive_status(result.status), 0);
        }
        write_resp(req.device_io_request, NtStatus::SUCCESS, result.written)
    }

    fn handle_query_information(
        &self,
        req: ServerDriveQueryInformationRequest,
    ) -> PduResult<Vec<SvcMessage>> {
        let Some(cb) = self.callback() else {
            return query_info_resp(req.device_io_request, NtStatus::ACCESS_DENIED, None);
        };
        let Some(handle) = self.handles.get(&req.device_io_request.file_id) else {
            return query_info_resp(req.device_io_request, NtStatus::NO_SUCH_FILE, None);
        };
        let entry = if handle.path.is_root() {
            RdpDriveEntry {
                name: "/".to_owned(),
                is_dir: true,
                size: 0,
                modified_ms: 0,
                read_only: false,
            }
        } else {
            match cb.stat(handle.path.as_str().to_owned()) {
                Some(e) => e,
                None => return query_info_resp(req.device_io_request, NtStatus::NO_SUCH_FILE, None),
            }
        };

        let attrs = file_attributes_for(&entry);
        let ft = millis_to_filetime(entry.modified_ms);
        let size_i64 = i64::try_from(entry.size).unwrap_or(i64::MAX);
        let is_delete_pending = self.pending_deletes.contains_key(&handle.path);

        let info = if req.file_info_class_lvl == FileInformationClassLevel::FILE_BASIC_INFORMATION {
            Some(FileInformationClass::Basic(FileBasicInformation {
                creation_time: ft,
                last_access_time: ft,
                last_write_time: ft,
                change_time: ft,
                file_attributes: attrs,
            }))
        } else if req.file_info_class_lvl == FileInformationClassLevel::FILE_STANDARD_INFORMATION {
            Some(FileInformationClass::Standard(FileStandardInformation {
                allocation_size: size_i64,
                end_of_file: size_i64,
                number_of_links: u32::from(!is_delete_pending),
                delete_pending: if is_delete_pending {
                    Boolean::True
                } else {
                    Boolean::False
                },
                directory: if entry.is_dir {
                    Boolean::True
                } else {
                    Boolean::False
                },
            }))
        } else if req.file_info_class_lvl
            == FileInformationClassLevel::FILE_ATTRIBUTE_TAG_INFORMATION
        {
            Some(FileInformationClass::AttributeTag(
                FileAttributeTagInformation {
                    file_attributes: attrs,
                    reparse_tag: 0,
                },
            ))
        } else {
            return query_info_resp(req.device_io_request, NtStatus::NOT_SUPPORTED, None);
        };

        query_info_resp(req.device_io_request, NtStatus::SUCCESS, info)
    }

    fn handle_query_volume_information(
        &self,
        req: ServerDriveQueryVolumeInformationRequest,
    ) -> PduResult<Vec<SvcMessage>> {
        if !self.handles.contains_key(&req.device_io_request.file_id) {
            return Ok(vec![SvcMessage::from(
                RdpdrPdu::ClientDriveQueryVolumeInformationResponse(
                    ClientDriveQueryVolumeInformationResponse {
                        device_io_reply: DeviceIoResponse::new(
                            req.device_io_request,
                            NtStatus::NO_SUCH_FILE,
                        ),
                        buffer: None,
                    },
                ),
            )]);
        }

        let buffer = if req.fs_info_class_lvl
            == FileSystemInformationClassLevel::FILE_FS_FULL_SIZE_INFORMATION
        {
            Some(FileSystemInformationClass::FileFsFullSizeInformation(
                FileFsFullSizeInformation {
                    total_alloc_units: NOMINAL_TOTAL_UNITS,
                    caller_available_alloc_units: NOMINAL_FREE_UNITS,
                    actual_available_alloc_units: NOMINAL_FREE_UNITS,
                    sectors_per_alloc_unit: SECTORS_PER_UNIT,
                    bytes_per_sector: BYTES_PER_SECTOR,
                },
            ))
        } else if req.fs_info_class_lvl == FileSystemInformationClassLevel::FILE_FS_SIZE_INFORMATION
        {
            Some(FileSystemInformationClass::FileFsSizeInformation(
                FileFsSizeInformation {
                    total_alloc_units: NOMINAL_TOTAL_UNITS,
                    available_alloc_units: NOMINAL_FREE_UNITS,
                    sectors_per_alloc_unit: SECTORS_PER_UNIT,
                    bytes_per_sector: BYTES_PER_SECTOR,
                },
            ))
        } else if req.fs_info_class_lvl
            == FileSystemInformationClassLevel::FILE_FS_ATTRIBUTE_INFORMATION
        {
            Some(FileSystemInformationClass::FileFsAttributeInformation(
                FileFsAttributeInformation {
                    file_system_attributes: FileSystemAttributes::FILE_CASE_SENSITIVE_SEARCH
                        | FileSystemAttributes::FILE_CASE_PRESERVED_NAMES
                        | FileSystemAttributes::FILE_UNICODE_ON_DISK,
                    max_component_name_len: 255,
                    file_system_name: "FAT32".to_owned(),
                },
            ))
        } else if req.fs_info_class_lvl
            == FileSystemInformationClassLevel::FILE_FS_VOLUME_INFORMATION
        {
            Some(FileSystemInformationClass::FileFsVolumeInformation(
                FileFsVolumeInformation {
                    volume_creation_time: WINDOWS_EPOCH_100NS,
                    volume_serial_number: 0x4841_564E,
                    supports_objects: Boolean::False,
                    volume_label: "HAVEN_SAF".to_owned(),
                },
            ))
        } else {
            return Ok(vec![SvcMessage::from(
                RdpdrPdu::ClientDriveQueryVolumeInformationResponse(
                    ClientDriveQueryVolumeInformationResponse {
                        device_io_reply: DeviceIoResponse::new(
                            req.device_io_request,
                            NtStatus::NOT_SUPPORTED,
                        ),
                        buffer: None,
                    },
                ),
            )]);
        };

        Ok(vec![SvcMessage::from(
            RdpdrPdu::ClientDriveQueryVolumeInformationResponse(
                ClientDriveQueryVolumeInformationResponse {
                    device_io_reply: DeviceIoResponse::new(
                        req.device_io_request,
                        NtStatus::SUCCESS,
                    ),
                    buffer,
                },
            ),
        )])
    }

    fn handle_query_directory(
        &mut self,
        req: ServerDriveQueryDirectoryRequest,
    ) -> PduResult<Vec<SvcMessage>> {
        let Some(cb) = self.callback() else {
            return query_dir_resp(
                req.device_io_request,
                req.file_info_class_lvl,
                Err(NtStatus::ACCESS_DENIED),
            );
        };
        let Some(handle) = self.handles.get_mut(&req.device_io_request.file_id) else {
            return query_dir_resp(
                req.device_io_request,
                req.file_info_class_lvl,
                Err(NtStatus::NO_SUCH_FILE),
            );
        };
        if !handle.is_dir {
            return query_dir_resp(
                req.device_io_request,
                req.file_info_class_lvl,
                Err(NtStatus::NOT_A_DIRECTORY),
            );
        }

        let next_entry = if req.initial_query == 0 {
            match handle.listing.as_ref() {
                Some(entries) if handle.listing_cursor < entries.len() => {
                    let entry = entries[handle.listing_cursor].clone();
                    handle.listing_cursor += 1;
                    Ok(entry)
                }
                _ => Err(NtStatus::NO_MORE_FILES),
            }
        } else {
            handle.listing = None;
            handle.listing_cursor = 0;
            match SafPath::parse_query(&req.path) {
                Err(InvalidSafPath) => Err(NtStatus::OBJECT_NAME_INVALID),
                Ok((dir_path, pattern)) => {
                    if !contains_wildcards(pattern) {
                        match dir_path.join_literal(pattern) {
                            Err(InvalidSafPath) => Err(NtStatus::OBJECT_NAME_INVALID),
                            Ok(target_path) => cb
                                .stat(target_path.as_str().to_owned())
                                .ok_or(NtStatus::NO_SUCH_FILE),
                        }
                    } else {
                        let list_res = cb.list_dir(dir_path.as_str().to_owned());
                        if list_res.status != RdpDriveStatus::Ok {
                            Err(map_drive_status(list_res.status))
                        } else {
                            let mut matched = Vec::new();
                            if !dir_path.is_root() && is_name_in_expression(".", pattern) {
                                matched.push(RdpDriveEntry {
                                    name: ".".to_owned(),
                                    is_dir: true,
                                    size: 0,
                                    modified_ms: 0,
                                    read_only: false,
                                });
                                matched.push(RdpDriveEntry {
                                    name: "..".to_owned(),
                                    is_dir: true,
                                    size: 0,
                                    modified_ms: 0,
                                    read_only: false,
                                });
                            }
                            for entry in list_res.entries {
                                if matches!(entry.name.as_str(), "." | "..") {
                                    continue;
                                }
                                if is_name_in_expression(&entry.name, pattern) {
                                    matched.push(entry);
                                }
                            }
                            if matched.is_empty() {
                                Err(NtStatus::NO_SUCH_FILE)
                            } else {
                                let first = matched[0].clone();
                                handle.listing = Some(matched);
                                handle.listing_cursor = 1;
                                Ok(first)
                            }
                        }
                    }
                }
            }
        };

        query_dir_resp(req.device_io_request, req.file_info_class_lvl, next_entry)
    }

    fn handle_set_information(
        &mut self,
        req: ServerDriveSetInformationRequest,
    ) -> PduResult<Vec<SvcMessage>> {
        let file_id = req.device_io_request.file_id;
        let Some(cb) = self.callback() else {
            return set_info_resp(&req, NtStatus::ACCESS_DENIED);
        };
        if !self.handles.contains_key(&file_id) {
            return set_info_resp(&req, NtStatus::NO_SUCH_FILE);
        }

        let status = match &req.set_buffer {
            FileInformationClass::Disposition(info) => {
                let handle = self.handles.get(&file_id).expect("checked above");
                if !handle.may_delete {
                    NtStatus::ACCESS_DENIED
                } else if info.delete_pending == 0 {
                    self.pending_deletes.remove(&handle.path);
                    NtStatus::SUCCESS
                } else if handle.path.is_root() {
                    NtStatus::ACCESS_DENIED
                } else {
                    match cb.stat(handle.path.as_str().to_owned()) {
                        None => NtStatus::NO_SUCH_FILE,
                        Some(entry) if entry.read_only => status_cannot_delete(),
                        Some(entry) if entry.is_dir => {
                            let listed = cb.list_dir(handle.path.as_str().to_owned());
                            if listed.status != RdpDriveStatus::Ok {
                                map_drive_status(listed.status)
                            } else if !listed.entries.is_empty() {
                                NtStatus::DIRECTORY_NOT_EMPTY
                            } else {
                                self.pending_deletes.insert(handle.path.clone(), true);
                                NtStatus::SUCCESS
                            }
                        }
                        Some(_) => {
                            self.pending_deletes.insert(handle.path.clone(), false);
                            NtStatus::SUCCESS
                        }
                    }
                }
            }
            FileInformationClass::Rename(info) => {
                let (from_path, is_dir, may_delete) = {
                    let h = self.handles.get(&file_id).expect("checked above");
                    (h.path.clone(), h.is_dir, h.may_delete)
                };
                if !may_delete || from_path.is_root() {
                    NtStatus::ACCESS_DENIED
                } else if self.pending_deletes.contains_key(&from_path)
                    || (is_dir
                        && self
                            .handles
                            .values()
                            .any(|other| from_path.is_strict_prefix_of(&other.path)))
                {
                    NtStatus::ACCESS_DENIED
                } else {
                    match SafPath::parse(&info.file_name) {
                        Err(InvalidSafPath) => NtStatus::OBJECT_NAME_INVALID,
                        Ok(to_path) if to_path.is_root() => NtStatus::OBJECT_NAME_INVALID,
                        Ok(to_path) => {
                            if self.has_delete_pending_on_or_above(&to_path) {
                                NtStatus::DELETE_PENDING
                            } else if to_path == from_path {
                                NtStatus::SUCCESS
                            } else {
                                let replace = info.replace_if_exists == Boolean::True;
                                let target_open =
                                    self.handles.values().any(|other| other.path == to_path);
                                if target_open {
                                    NtStatus::ACCESS_DENIED
                                } else {
                                    cb.close_path(from_path.as_str().to_owned());
                                    let st = cb.rename_path(
                                        from_path.as_str().to_owned(),
                                        to_path.as_str().to_owned(),
                                        replace,
                                    );
                                    if st == RdpDriveStatus::Ok {
                                        for h in self.handles.values_mut() {
                                            if h.path == from_path {
                                                h.path = to_path.clone();
                                            }
                                        }
                                        self.locks.rename_path(&from_path, &to_path);
                                    }
                                    map_drive_status(st)
                                }
                            }
                        }
                    }
                }
            }
            FileInformationClass::EndOfFile(info) => {
                let handle = self.handles.get(&file_id).expect("checked above");
                if handle.is_dir || !handle.may_write {
                    NtStatus::ACCESS_DENIED
                } else if info.end_of_file < 0 {
                    NtStatus::INVALID_PARAMETER
                } else {
                    let size = u64::try_from(info.end_of_file).unwrap_or(0);
                    map_drive_status(cb.truncate(handle.path.as_str().to_owned(), size))
                }
            }
            FileInformationClass::Allocation(_) | FileInformationClass::Basic(_) => {
                NtStatus::SUCCESS
            }
            _ => NtStatus::SUCCESS,
        };

        set_info_resp(&req, status)
    }

    fn handle_lock_control(
        &mut self,
        req: ServerDriveLockControlRequest,
    ) -> PduResult<Vec<SvcMessage>> {
        let file_id = req.device_io_request.file_id;
        let Some(handle) = self.handles.get(&file_id) else {
            return lock_resp(req.device_io_request, NtStatus::NO_SUCH_FILE);
        };
        if handle.is_dir {
            return lock_resp(req.device_io_request, NtStatus::INVALID_PARAMETER);
        }
        let path = handle.path.clone();
        let status = match req.operation {
            LockOperation::Shared => self.locks.lock(file_id, &path, &req.locks, false),
            LockOperation::Exclusive => self.locks.lock(file_id, &path, &req.locks, true),
            LockOperation::Unlock | LockOperation::UnlockMultiple => {
                self.locks.unlock(file_id, &path, &req.locks)
            }
        };
        lock_resp(req.device_io_request, status)
    }
}

impl Drop for HavenRdpdrBackend {
    fn drop(&mut self) {
        self.close_all_handles();
    }
}

impl RdpdrBackend for HavenRdpdrBackend {
    fn reset(&mut self) -> PduResult<()> {
        self.close_all_handles();
        Ok(())
    }

    fn handle_server_device_announce_response(
        &mut self,
        pdu: ServerDeviceAnnounceResponse,
    ) -> PduResult<()> {
        if pdu.device_id == SAF_DRIVE_DEVICE_ID {
            if pdu.result_code == NtStatus::SUCCESS {
                info!("RDPDR server accepted shared folder (device_id={})", pdu.device_id);
            } else {
                warn!(
                    "RDPDR server rejected shared folder (device_id={}, status={:?})",
                    pdu.device_id, pdu.result_code
                );
            }
        }
        Ok(())
    }

    fn handle_scard_call(
        &mut self,
        _req: DeviceControlRequest<ScardIoCtlCode>,
        _call: ScardCall,
    ) -> PduResult<Vec<SvcMessage>> {
        Ok(Vec::new())
    }

    fn handle_drive_io_request(&mut self, req: ServerDriveIoRequest) -> PduResult<Vec<SvcMessage>> {
        match req {
            ServerDriveIoRequest::ServerCreateDriveRequest(inner) => self.handle_create(inner),
            ServerDriveIoRequest::DeviceReadRequest(inner) => self.handle_read(inner),
            ServerDriveIoRequest::DeviceWriteRequest(inner) => self.handle_write(inner),
            ServerDriveIoRequest::DeviceCloseRequest(inner) => {
                self.close_handle(inner.device_io_request.file_id);
                Ok(vec![SvcMessage::from(RdpdrPdu::DeviceCloseResponse(
                    DeviceCloseResponse {
                        device_io_response: DeviceIoResponse::new(
                            inner.device_io_request,
                            NtStatus::SUCCESS,
                        ),
                    },
                ))])
            }
            ServerDriveIoRequest::DeviceFlushBuffersRequest(inner) => {
                if let Some(handle) = self.handles.get(&inner.device_io_request.file_id) {
                    if let Some(cb) = self.callback() {
                        cb.close_path(handle.path.as_str().to_owned());
                    }
                }
                Ok(vec![SvcMessage::from(RdpdrPdu::DeviceFlushBuffersResponse(
                    DeviceFlushBuffersResponse {
                        device_io_response: DeviceIoResponse::new(
                            inner.device_io_request,
                            NtStatus::SUCCESS,
                        ),
                    },
                ))])
            }
            ServerDriveIoRequest::ServerDriveNotifyChangeDirectoryRequest(_) => Ok(Vec::new()),
            ServerDriveIoRequest::ServerDriveQueryDirectoryRequest(inner) => {
                self.handle_query_directory(inner)
            }
            ServerDriveIoRequest::ServerDriveQueryInformationRequest(inner) => {
                self.handle_query_information(inner)
            }
            ServerDriveIoRequest::ServerDriveQueryVolumeInformationRequest(inner) => {
                self.handle_query_volume_information(inner)
            }
            ServerDriveIoRequest::ServerDriveSetInformationRequest(inner) => {
                self.handle_set_information(inner)
            }
            ServerDriveIoRequest::DeviceControlRequest(inner) => Ok(vec![SvcMessage::from(
                RdpdrPdu::DeviceControlResponse(DeviceControlResponse {
                    device_io_reply: DeviceIoResponse::new(inner.header, NtStatus::SUCCESS),
                    output_buffer: None,
                }),
            )]),
            ServerDriveIoRequest::ServerDriveLockControlRequest(inner) => {
                self.handle_lock_control(inner)
            }
            ServerDriveIoRequest::ServerDriveQuerySecurityRequest(inner) => Ok(vec![
                SvcMessage::from(RdpdrPdu::ClientDriveQuerySecurityResponse(
                    ClientDriveQuerySecurityResponse {
                        device_io_response: DeviceIoResponse::new(
                            inner.device_io_request,
                            NtStatus::NOT_SUPPORTED,
                        ),
                        security_descriptor: None,
                    },
                )),
            ]),
            ServerDriveIoRequest::ServerDriveSetSecurityRequest(inner) => {
                let response =
                    ClientDriveSetSecurityResponse::new(&inner, NtStatus::NOT_SUPPORTED)
                        .map_err(|e| encode_err!(e))?;
                Ok(vec![SvcMessage::from(
                    RdpdrPdu::ClientDriveSetSecurityResponse(response),
                )])
            }
        }
    }
}

fn create_error_resp(req: DeviceIoRequest, status: NtStatus) -> PduResult<Vec<SvcMessage>> {
    Ok(vec![SvcMessage::from(RdpdrPdu::DeviceCreateResponse(
        DeviceCreateResponse {
            device_io_reply: DeviceIoResponse::new(req, status),
            file_id: 0,
            information: Information::empty(),
        },
    ))])
}

fn read_resp(
    req: DeviceIoRequest,
    status: NtStatus,
    read_data: Vec<u8>,
) -> PduResult<Vec<SvcMessage>> {
    Ok(vec![SvcMessage::from(RdpdrPdu::DeviceReadResponse(
        DeviceReadResponse {
            device_io_reply: DeviceIoResponse::new(req, status),
            read_data,
        },
    ))])
}

fn write_resp(req: DeviceIoRequest, status: NtStatus, length: u32) -> PduResult<Vec<SvcMessage>> {
    Ok(vec![SvcMessage::from(RdpdrPdu::DeviceWriteResponse(
        DeviceWriteResponse {
            device_io_reply: DeviceIoResponse::new(req, status),
            length,
        },
    ))])
}

fn query_info_resp(
    req: DeviceIoRequest,
    status: NtStatus,
    buffer: Option<FileInformationClass>,
) -> PduResult<Vec<SvcMessage>> {
    Ok(vec![SvcMessage::from(
        RdpdrPdu::ClientDriveQueryInformationResponse(ClientDriveQueryInformationResponse {
            device_io_response: DeviceIoResponse::new(req, status),
            buffer,
        }),
    )])
}

fn query_dir_resp(
    req: DeviceIoRequest,
    class_lvl: FileInformationClassLevel,
    entry: Result<RdpDriveEntry, NtStatus>,
) -> PduResult<Vec<SvcMessage>> {
    match entry {
        Err(status) => Ok(vec![SvcMessage::from(
            RdpdrPdu::ClientDriveQueryDirectoryResponse(ClientDriveQueryDirectoryResponse {
                device_io_reply: DeviceIoResponse::new(req, status),
                buffer: None,
            }),
        )]),
        Ok(e) => {
            let ft = millis_to_filetime(e.modified_ms);
            let size = i64::try_from(e.size).unwrap_or(i64::MAX);
            let attrs = file_attributes_for(&e);
            let buffer = if class_lvl == FileInformationClassLevel::FILE_BOTH_DIRECTORY_INFORMATION
            {
                Some(FileInformationClass::BothDirectory(
                    FileBothDirectoryInformation::new(ft, ft, ft, ft, size, attrs, e.name),
                ))
            } else if class_lvl == FileInformationClassLevel::FILE_FULL_DIRECTORY_INFORMATION {
                Some(FileInformationClass::FullDirectory(
                    FileFullDirectoryInformation::new(ft, ft, ft, ft, size, attrs, e.name),
                ))
            } else if class_lvl == FileInformationClassLevel::FILE_DIRECTORY_INFORMATION {
                Some(FileInformationClass::Directory(
                    FileDirectoryInformation::new(ft, ft, ft, ft, size, attrs, e.name),
                ))
            } else if class_lvl == FileInformationClassLevel::FILE_NAMES_INFORMATION {
                Some(FileInformationClass::Names(FileNamesInformation::new(
                    e.name,
                )))
            } else {
                return Ok(vec![SvcMessage::from(
                    RdpdrPdu::ClientDriveQueryDirectoryResponse(
                        ClientDriveQueryDirectoryResponse {
                            device_io_reply: DeviceIoResponse::new(req, NtStatus::NOT_SUPPORTED),
                            buffer: None,
                        },
                    ),
                )]);
            };
            Ok(vec![SvcMessage::from(
                RdpdrPdu::ClientDriveQueryDirectoryResponse(ClientDriveQueryDirectoryResponse {
                    device_io_reply: DeviceIoResponse::new(req, NtStatus::SUCCESS),
                    buffer,
                }),
            )])
        }
    }
}

fn set_info_resp(
    req: &ServerDriveSetInformationRequest,
    status: NtStatus,
) -> PduResult<Vec<SvcMessage>> {
    Ok(vec![SvcMessage::from(
        RdpdrPdu::ClientDriveSetInformationResponse(
            ClientDriveSetInformationResponse::new(req, status).map_err(|e| encode_err!(e))?,
        ),
    )])
}

fn lock_resp(req: DeviceIoRequest, status: NtStatus) -> PduResult<Vec<SvcMessage>> {
    Ok(vec![SvcMessage::from(
        RdpdrPdu::ClientDriveLockControlResponse(ClientDriveLockControlResponse::new(req, status)),
    )])
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use ironrdp_rdpdr::pdu::efs::{
        DeviceCloseRequest, FileDispositionInformation, FileRenameInformation, MajorFunction,
        MinorFunction, SharedAccess,
    };

    use super::*;
    use crate::{RdpDriveListResult, RdpDriveReadResult, RdpDriveWriteResult};

    #[derive(Default)]
    struct MemFs {
        dirs: Vec<String>,
        files: HashMap<String, Vec<u8>>,
    }

    struct MockDriveCb(Mutex<MemFs>);

    impl MockDriveCb {
        fn new() -> Arc<Self> {
            let mut fs = MemFs::default();
            fs.dirs.push("/".to_owned());
            Arc::new(Self(Mutex::new(fs)))
        }
    }

    impl DriveCallback for MockDriveCb {
        fn stat(&self, path: String) -> Option<RdpDriveEntry> {
            let fs = self.0.lock().unwrap();
            let name = if path == "/" {
                "/".to_owned()
            } else {
                path.rsplit_once('/')?.1.to_owned()
            };
            if fs.dirs.contains(&path) {
                return Some(RdpDriveEntry {
                    name,
                    is_dir: true,
                    size: 0,
                    modified_ms: 1_700_000_000_000,
                    read_only: false,
                });
            }
            let data = fs.files.get(&path)?;
            Some(RdpDriveEntry {
                name,
                is_dir: false,
                size: data.len() as u64,
                modified_ms: 1_700_000_000_000,
                read_only: false,
            })
        }

        fn list_dir(&self, path: String) -> RdpDriveListResult {
            let fs = self.0.lock().unwrap();
            if !fs.dirs.contains(&path) {
                return RdpDriveListResult {
                    status: RdpDriveStatus::NotFound,
                    entries: Vec::new(),
                };
            }
            let child_of = |candidate: &str| -> Option<String> {
                if candidate == "/" {
                    return None;
                }
                let (parent, leaf) = candidate.rsplit_once('/')?;
                let parent_norm = if parent.is_empty() { "/" } else { parent };
                (parent_norm == path).then(|| leaf.to_owned())
            };
            let mut entries = Vec::new();
            for dir in &fs.dirs {
                if let Some(name) = child_of(dir) {
                    entries.push(RdpDriveEntry {
                        name,
                        is_dir: true,
                        size: 0,
                        modified_ms: 1_700_000_000_000,
                        read_only: false,
                    });
                }
            }
            for (file_path, bytes) in &fs.files {
                if let Some(name) = child_of(file_path) {
                    entries.push(RdpDriveEntry {
                        name,
                        is_dir: false,
                        size: bytes.len() as u64,
                        modified_ms: 1_700_000_000_000,
                        read_only: false,
                    });
                }
            }
            entries.sort_by(|a, b| a.name.cmp(&b.name));
            RdpDriveListResult {
                status: RdpDriveStatus::Ok,
                entries,
            }
        }

        fn mkdir(&self, path: String) -> RdpDriveStatus {
            let mut fs = self.0.lock().unwrap();
            if fs.dirs.contains(&path) || fs.files.contains_key(&path) {
                return RdpDriveStatus::AlreadyExists;
            }
            fs.dirs.push(path);
            RdpDriveStatus::Ok
        }

        fn create_file(&self, path: String, overwrite: bool) -> RdpDriveStatus {
            let mut fs = self.0.lock().unwrap();
            if fs.dirs.contains(&path) {
                return RdpDriveStatus::IsADirectory;
            }
            if !overwrite && fs.files.contains_key(&path) {
                return RdpDriveStatus::AlreadyExists;
            }
            fs.files.insert(path, Vec::new());
            RdpDriveStatus::Ok
        }

        fn read_at(&self, path: String, offset: u64, length: u32) -> RdpDriveReadResult {
            let fs = self.0.lock().unwrap();
            let Some(bytes) = fs.files.get(&path) else {
                return RdpDriveReadResult {
                    status: RdpDriveStatus::NotFound,
                    data: Vec::new(),
                };
            };
            let start = usize::try_from(offset).unwrap_or(bytes.len()).min(bytes.len());
            let end = start.saturating_add(length as usize).min(bytes.len());
            RdpDriveReadResult {
                status: RdpDriveStatus::Ok,
                data: bytes[start..end].to_vec(),
            }
        }

        fn write_at(&self, path: String, offset: u64, data: Vec<u8>) -> RdpDriveWriteResult {
            let mut fs = self.0.lock().unwrap();
            let Some(bytes) = fs.files.get_mut(&path) else {
                return RdpDriveWriteResult {
                    status: RdpDriveStatus::NotFound,
                    written: 0,
                };
            };
            let start = offset as usize;
            let end = start + data.len();
            if bytes.len() < end {
                bytes.resize(end, 0);
            }
            bytes[start..end].copy_from_slice(&data);
            RdpDriveWriteResult {
                status: RdpDriveStatus::Ok,
                written: data.len() as u32,
            }
        }

        fn truncate(&self, path: String, size: u64) -> RdpDriveStatus {
            let mut fs = self.0.lock().unwrap();
            let Some(bytes) = fs.files.get_mut(&path) else {
                return RdpDriveStatus::NotFound;
            };
            bytes.resize(size as usize, 0);
            RdpDriveStatus::Ok
        }

        fn delete_path(&self, path: String, is_dir: bool) -> RdpDriveStatus {
            let mut fs = self.0.lock().unwrap();
            if is_dir {
                if let Some(pos) = fs.dirs.iter().position(|d| d == &path) {
                    fs.dirs.remove(pos);
                    RdpDriveStatus::Ok
                } else {
                    RdpDriveStatus::NotFound
                }
            } else if fs.files.remove(&path).is_some() {
                RdpDriveStatus::Ok
            } else {
                RdpDriveStatus::NotFound
            }
        }

        fn rename_path(
            &self,
            from_path: String,
            to_path: String,
            replace_if_exists: bool,
        ) -> RdpDriveStatus {
            let mut fs = self.0.lock().unwrap();
            if fs.dirs.contains(&to_path) {
                return RdpDriveStatus::AccessDenied;
            }
            if !replace_if_exists && fs.files.contains_key(&to_path) {
                return RdpDriveStatus::AlreadyExists;
            }
            let Some(bytes) = fs.files.remove(&from_path) else {
                return RdpDriveStatus::NotFound;
            };
            fs.files.insert(to_path, bytes);
            RdpDriveStatus::Ok
        }

        fn close_path(&self, _path: String) {}
    }

    fn backend_with_cb(cb: Arc<MockDriveCb>) -> HavenRdpdrBackend {
        let state = Arc::new(RwLock::new(crate::SessionState {
            connected: true,
            framebuffer: None,
            dirty_rects: Vec::new(),
            frame_callback: None,
            clipboard_callback: None,
            audio_callback: None,
            drive_callback: Some(cb),
            camera_callback: None,
            mic_callback: None,
            session_callback: None,
            pointer_callback: None,
            avc_decoder: None,
            shutdown: false,
            perf_log: Vec::new(),
        }));
        HavenRdpdrBackend::new(state)
    }

    fn io_header(file_id: u32, major: MajorFunction) -> DeviceIoRequest {
        DeviceIoRequest {
            device_id: SAF_DRIVE_DEVICE_ID,
            file_id,
            completion_id: 1,
            major_function: major,
            minor_function: MinorFunction::from(0),
        }
    }

    fn decode_status_and_u32(msg: &SvcMessage) -> (NtStatus, u32) {
        let raw = msg.encode_unframed_pdu().expect("encode pdu");
        let status = NtStatus::from(u32::from_le_bytes(raw[12..16].try_into().unwrap()));
        let trailing = u32::from_le_bytes(raw[16..20].try_into().unwrap());
        (status, trailing)
    }

    #[test]
    fn saf_path_blocks_traversal_slashes_and_control_chars() {
        assert_eq!(SafPath::parse(r"\").unwrap().as_str(), "/");
        assert_eq!(
            SafPath::parse(r"\Docs\notes.txt").unwrap().as_str(),
            "/Docs/notes.txt"
        );
        assert_eq!(SafPath::parse(r"\..\etc\passwd"), Err(InvalidSafPath));
        assert_eq!(SafPath::parse(r"\Docs\..\..\secret"), Err(InvalidSafPath));
        assert_eq!(SafPath::parse(r"\Docs/sub"), Err(InvalidSafPath));
        assert_eq!(SafPath::parse("\\nul\0byte"), Err(InvalidSafPath));
        assert_eq!(SafPath::parse(r"\trailing."), Err(InvalidSafPath));
    }

    #[test]
    fn wildcard_matches_dos_and_standard_expressions() {
        assert!(is_name_in_expression("report.TXT", "*.txt"));
        assert!(is_name_in_expression("README", "*.*"));
        assert!(is_name_in_expression("README", "<\""));
        assert!(!is_name_in_expression("notes.txt", "<\""));
        assert!(is_name_in_expression("a.b.txt", "<.txt"));
        assert!(is_name_in_expression("ab.txt", "a>.txt"));
        assert!(!is_name_in_expression("abc.txt", "a>.txt"));
    }

    #[test]
    fn create_write_read_rename_and_disposition_delete_roundtrip() {
        let cb = MockDriveCb::new();
        let mut backend = backend_with_cb(Arc::clone(&cb));

        // 1. Create `\hello.txt` with GENERIC_READ | GENERIC_WRITE | DELETE.
        let create_msgs = backend
            .handle_drive_io_request(ServerDriveIoRequest::ServerCreateDriveRequest(
                DeviceCreateRequest {
                    device_io_request: io_header(0, MajorFunction::Create),
                    desired_access: DesiredAccess::GENERIC_READ
                        | DesiredAccess::GENERIC_WRITE
                        | DesiredAccess::DELETE,
                    allocation_size: 0,
                    file_attributes: FileAttributes::FILE_ATTRIBUTE_NORMAL,
                    shared_access: SharedAccess::FILE_SHARE_READ | SharedAccess::FILE_SHARE_WRITE,
                    create_disposition: CreateDisposition::FILE_CREATE,
                    create_options: CreateOptions::FILE_NON_DIRECTORY_FILE,
                    path: r"\hello.txt".to_owned(),
                },
            ))
            .unwrap();
        let (st, file_id) = decode_status_and_u32(&create_msgs[0]);
        assert_eq!(st, NtStatus::SUCCESS);

        // 2. Write payload at offset 0.
        let write_msgs = backend
            .handle_drive_io_request(ServerDriveIoRequest::DeviceWriteRequest(
                DeviceWriteRequest {
                    device_io_request: io_header(file_id, MajorFunction::Write),
                    offset: 0,
                    write_data: b"haven-saf-rdpdr".to_vec(),
                },
            ))
            .unwrap();
        let (st, written) = decode_status_and_u32(&write_msgs[0]);
        assert_eq!(st, NtStatus::SUCCESS);
        assert_eq!(written, 15);

        // 3. Read back slice at offset 6.
        let read_msgs = backend
            .handle_drive_io_request(ServerDriveIoRequest::DeviceReadRequest(DeviceReadRequest {
                device_io_request: io_header(file_id, MajorFunction::Read),
                length: 9,
                offset: 6,
            }))
            .unwrap();
        let raw_read = read_msgs[0].encode_unframed_pdu().unwrap();
        let read_status = NtStatus::from(u32::from_le_bytes(raw_read[12..16].try_into().unwrap()));
        let read_len = u32::from_le_bytes(raw_read[16..20].try_into().unwrap()) as usize;
        assert_eq!(read_status, NtStatus::SUCCESS);
        assert_eq!(&raw_read[20..20 + read_len], b"saf-rdpdr");

        // 4. Rename `\hello.txt` -> `\renamed.txt`.
        let rename_msgs = backend
            .handle_drive_io_request(ServerDriveIoRequest::ServerDriveSetInformationRequest(
                ServerDriveSetInformationRequest {
                    device_io_request: io_header(file_id, MajorFunction::SetInformation),
                    set_buffer: FileInformationClass::Rename(FileRenameInformation {
                        replace_if_exists: Boolean::False,
                        file_name: r"\renamed.txt".to_owned(),
                    }),
                },
            ))
            .unwrap();
        let (st, _) = decode_status_and_u32(&rename_msgs[0]);
        assert_eq!(st, NtStatus::SUCCESS);
        assert!(cb.stat("/renamed.txt".to_owned()).is_some());
        assert!(cb.stat("/hello.txt".to_owned()).is_none());

        // 5. Mark delete-pending via FileDispositionInformation, verify deleted only after close.
        let disp_msgs = backend
            .handle_drive_io_request(ServerDriveIoRequest::ServerDriveSetInformationRequest(
                ServerDriveSetInformationRequest {
                    device_io_request: io_header(file_id, MajorFunction::SetInformation),
                    set_buffer: FileInformationClass::Disposition(FileDispositionInformation {
                        delete_pending: 1,
                    }),
                },
            ))
            .unwrap();
        assert_eq!(decode_status_and_u32(&disp_msgs[0]).0, NtStatus::SUCCESS);
        assert!(
            cb.stat("/renamed.txt".to_owned()).is_some(),
            "file must stay until last handle closes"
        );

        backend
            .handle_drive_io_request(ServerDriveIoRequest::DeviceCloseRequest(
                DeviceCloseRequest {
                    device_io_request: io_header(file_id, MajorFunction::Close),
                },
            ))
            .unwrap();
        assert!(
            cb.stat("/renamed.txt".to_owned()).is_none(),
            "file must be removed when last handle closes"
        );
    }

    #[test]
    fn directory_query_enumerates_dot_entries_on_subdir_and_filters_wildcards() {
        let cb = MockDriveCb::new();
        assert_eq!(cb.mkdir("/sub".to_owned()), RdpDriveStatus::Ok);
        assert_eq!(
            cb.create_file("/sub/a.txt".to_owned(), true),
            RdpDriveStatus::Ok
        );
        assert_eq!(
            cb.create_file("/sub/b.log".to_owned(), true),
            RdpDriveStatus::Ok
        );
        let mut backend = backend_with_cb(cb);

        let create_msgs = backend
            .handle_drive_io_request(ServerDriveIoRequest::ServerCreateDriveRequest(
                DeviceCreateRequest {
                    device_io_request: io_header(0, MajorFunction::Create),
                    desired_access: DesiredAccess::GENERIC_READ,
                    allocation_size: 0,
                    file_attributes: FileAttributes::FILE_ATTRIBUTE_DIRECTORY,
                    shared_access: SharedAccess::FILE_SHARE_READ,
                    create_disposition: CreateDisposition::FILE_OPEN,
                    create_options: CreateOptions::FILE_DIRECTORY_FILE,
                    path: r"\sub".to_owned(),
                },
            ))
            .unwrap();
        let (st, dir_id) = decode_status_and_u32(&create_msgs[0]);
        assert_eq!(st, NtStatus::SUCCESS);

        // Initial `*` query in non-root `/sub` returns `.`, followed by `..`, `a.txt`, `b.log`, then NO_MORE_FILES.
        let mut count = 0;
        let first = backend
            .handle_drive_io_request(ServerDriveIoRequest::ServerDriveQueryDirectoryRequest(
                ServerDriveQueryDirectoryRequest {
                    device_io_request: io_header(dir_id, MajorFunction::DirectoryControl),
                    file_info_class_lvl: FileInformationClassLevel::FILE_NAMES_INFORMATION,
                    initial_query: 1,
                    path: r"\sub\*".to_owned(),
                },
            ))
            .unwrap();
        assert_eq!(decode_status_and_u32(&first[0]).0, NtStatus::SUCCESS);
        count += 1;

        loop {
            let next = backend
                .handle_drive_io_request(ServerDriveIoRequest::ServerDriveQueryDirectoryRequest(
                    ServerDriveQueryDirectoryRequest {
                        device_io_request: io_header(dir_id, MajorFunction::DirectoryControl),
                        file_info_class_lvl: FileInformationClassLevel::FILE_NAMES_INFORMATION,
                        initial_query: 0,
                        path: String::new(),
                    },
                ))
                .unwrap();
            let (st, _) = decode_status_and_u32(&next[0]);
            if st == NtStatus::NO_MORE_FILES {
                break;
            }
            assert_eq!(st, NtStatus::SUCCESS);
            count += 1;
        }
        assert_eq!(count, 4, "expected '.', '..', 'a.txt', 'b.log'");
    }
}

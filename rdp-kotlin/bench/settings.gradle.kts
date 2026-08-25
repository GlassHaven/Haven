// Standalone on purpose. Haven consumes rdp-kotlin through includeBuild(), so
// adding the rig as a subproject there would put a diagnostic on the shipped
// build's graph. This build is only ever run by hand.
rootProject.name = "rdp-boundary-bench"

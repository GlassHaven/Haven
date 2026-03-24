{
  lib,
  stdenv,
  gradle,
  jdk21_headless,
  mkShell,
  writeShellScriptBin,
  jq,
  fetchurl,
  androidenv,
  sdkmanager,
  cmake,
  python313,
  cacert,
  termlibSrc,
  moshKotlinSrc,
  etKotlinSrc,
}:

let
  projectSrc = lib.cleanSource ../.;
  ndkVersion = "27.0.12077973";

  protocClassifier =
    {
      "aarch64-darwin" = "osx-aarch_64";
      "x86_64-darwin" = "osx-x86_64";
      "x86_64-linux" = "linux-x86_64";
      "aarch64-linux" = "linux-aarch_64";
    }.${stdenv.hostPlatform.system} or (throw "Unsupported host system for protoc: ${stdenv.hostPlatform.system}");

  protocHash =
    {
      "osx-aarch_64" = "sha256-vcQ+VBU5EfYlpQuvfU9rwFAIcSlkFBuu3VphEi+ToBY=";
      "osx-x86_64" = "sha256-Rh404Js5K39c5mlrG1OHESrFbMxECIxZncQwkF+jXkc=";
      "linux-x86_64" = "sha256-WulK2YboPwtSvYE54DbL+U3bujrTSL6OS69ERH1+GaQ=";
      "linux-aarch_64" = "sha256-zUQym/1PZeJVefdHr873R1kc2ERbcAyQaIH3XBYbPCU=";
    }.${protocClassifier};

  hostProtoc = fetchurl {
    url = "https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/4.29.3/protoc-4.29.3-${protocClassifier}.exe";
    hash = protocHash;
  };

  androidComposition = androidenv.composeAndroidPackages {
    platformVersions = [ "35" "36" ];
    buildToolsVersions = [ "35.0.0" "36.0.0" ];
    cmakeVersions = [ "3.31.6" ];
    abiVersions = [ "arm64-v8a" "x86_64" ];
    ndkVersions = [ ndkVersion ];
    includeNDK = true;
    includeEmulator = false;
    includeSystemImages = false;
    includeSources = false;
  };

  androidSdk = androidComposition.androidsdk;
  androidHome = "${androidSdk}/libexec/android-sdk";
  androidNdk = "${androidHome}/ndk/${ndkVersion}";
  androidCmake = "${androidHome}/cmake/3.31.6/bin/cmake";
  aapt2Binary = "${androidHome}/build-tools/36.0.0/aapt2";

  rnsWheel = fetchurl {
    url = "https://files.pythonhosted.org/packages/py3/r/rns/rns-1.1.4-py3-none-any.whl";
    hash = "sha256-sqF1q9ZNFYHdBYIGgyeT2/cFOjBMgZ/4vBQ6ecSct0c=";
  };

  rnshWheel = fetchurl {
    url = "https://files.pythonhosted.org/packages/py3/r/rnsh/rnsh-0.1.7-py3-none-any.whl";
    hash = "sha256-XbXujDBZ8Noe6Qez33lDutfc7WeQYXHtpKqGTrl8HGQ=";
  };

  pyserialWheel = fetchurl {
    url = "https://files.pythonhosted.org/packages/py2.py3/p/pyserial/pyserial-3.5-py2.py3-none-any.whl";
    hash = "sha256-xEUdtro5HKbKKZ+z7HuuZ6XFXd4XCWTHoUzu/sAvLPA=";
  };

  pycparserWheel = fetchurl {
    url = "https://files.pythonhosted.org/packages/py3/p/pycparser/pycparser-3.0-py3-none-any.whl";
    hash = "sha256-tydBQWmja31STBw+MYOaUhclB417L/A4ZWhEJmFgqZI=";
  };

  cryptographyArm64Wheel = fetchurl {
    url = "https://chaquo.com/pypi-13.1/cryptography/cryptography-42.0.8-0-cp313-cp313-android_24_arm64_v8a.whl";
    hash = "sha256-N/LM3HwFJf9EkPFT52KVeWiWCBZY7qt6lHn+RBDaVck=";
  };

  cryptographyX64Wheel = fetchurl {
    url = "https://chaquo.com/pypi-13.1/cryptography/cryptography-42.0.8-0-cp313-cp313-android_24_x86_64.whl";
    hash = "sha256-eoXWLp6w/vB/WafigwAj5GpHsoeR9BZw1ny2Mc2H644=";
  };

  cffiArm64Wheel = fetchurl {
    url = "https://chaquo.com/pypi-13.1/cffi/cffi-1.17.1-0-cp313-cp313-android_24_arm64_v8a.whl";
    hash = "sha256-CdxKjuHlH+wNEwQhxA8Z90mX+t0hRDVVhe+kQ2c3tqo=";
  };

  cffiX64Wheel = fetchurl {
    url = "https://chaquo.com/pypi-13.1/cffi/cffi-1.17.1-0-cp313-cp313-android_24_x86_64.whl";
    hash = "sha256-Bo2zfGa1D7knLp4i0ASBcXuWDzNus1qatpfO2HQp7rg=";
  };

  chaquopyLibffiArm64Wheel = fetchurl {
    url = "https://chaquo.com/pypi-13.1/chaquopy-libffi/chaquopy_libffi-3.3-3-py3-none-android_24_arm64_v8a.whl";
    hash = "sha256-hoc8L45eQ7B7ciM7814GrY2uQbqY4FViAtYdANetlS8=";
  };

  chaquopyLibffiX64Wheel = fetchurl {
    url = "https://chaquo.com/pypi-13.1/chaquopy-libffi/chaquopy_libffi-3.3-3-py3-none-android_24_x86_64.whl";
    hash = "sha256-v15KUz7+fBsmh8jbM+XJPBAt4SrqQNhgVw5ewR4p3DE=";
  };

  apkAndDeps = rec {
    apk = stdenv.mkDerivation {
      pname = "haven";
      version = "3.9.1";
      src = projectSrc;

      nativeBuildInputs = [
        gradle
        jdk21_headless
        python313
        cacert
      ];

      mitmCache = gradleDeps;
      __darwinAllowLocalNetworking = true;

      dontUseGradleCheck = true;
      gradleBuildTask = "assembleRelease";
      gradleUpdateTask = "assembleRelease";
      gradleFlags = [
        "-Dorg.gradle.java.home=${jdk21_headless}"
        "-Pandroid.aapt2FromMavenOverride=${aapt2Binary}"
      ];

      preBuild = ''
        set -euo pipefail

        export HOME="$TMPDIR"
        export _JAVA_OPTIONS="-Duser.home=$TMPDIR ''${_JAVA_OPTIONS-}"
        export GRADLE_USER_HOME="$TMPDIR/gradle-home"
        export JAVA_HOME="${jdk21_headless}"
        export ANDROID_HOME="${androidHome}"
        export ANDROID_SDK_ROOT="$ANDROID_HOME"
        export ANDROID_NDK_HOME="${androidNdk}"
        export ANDROID_NDK="$ANDROID_NDK_HOME"
        export CMAKE="${androidCmake}"
        export PATH="${python313}/bin:$PATH"
        export SSL_CERT_FILE="${cacert}/etc/ssl/certs/ca-bundle.crt"
        export NIX_SSL_CERT_FILE="$SSL_CERT_FILE"
        export REQUESTS_CA_BUNDLE="$SSL_CERT_FILE"
        export PIP_CERT="$SSL_CERT_FILE"
        export PIP_TRUSTED_HOST="pypi.org files.pythonhosted.org chaquo.com"
        export HAVEN_CA_BUNDLE="$SSL_CERT_FILE"
        export HAVEN_PIP_TRUSTED_HOSTS="pypi.org,files.pythonhosted.org,chaquo.com"

        mkdir -p "$HOME/.android"

        printf '%s\n' \
          "sdk.dir=$ANDROID_HOME" \
          "ndk.dir=$ANDROID_NDK_HOME" \
          "cmake.dir=${androidHome}/cmake/3.31.6" \
          > local.properties

        mkdir -p termlib mosh-kotlin et-kotlin
        cp -R ${termlibSrc}/. termlib/
        cp -R ${moshKotlinSrc}/. mosh-kotlin/
        cp -R ${etKotlinSrc}/. et-kotlin/
        chmod -R u+w termlib mosh-kotlin et-kotlin

        substituteInPlace termlib/lib/src/main/cpp/CMakeLists.txt \
          --replace-fail 'target_link_options (jni_cb_term PRIVATE "-Wl,-z,max-page-size=16384")' \
                          'target_link_options (jni_cb_term PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--build-id=none")'
        python3 - <<'PY'
from pathlib import Path
path = Path("core/local/src/main/cpp/CMakeLists.txt")
text = path.read_text()
old = "add_library(pty_bridge SHARED pty_bridge.c)\n"
new = "add_library(pty_bridge SHARED pty_bridge.c)\ntarget_link_options(pty_bridge PRIVATE \"-Wl,--build-id=none\")\n"
if old not in text:
    raise SystemExit("Failed to patch core/local/src/main/cpp/CMakeLists.txt for deterministic linker flags")
path.write_text(text.replace(old, new, 1))
PY

        mkdir -p .nix-tools
        rm -f .nix-tools/protoc
        cp ${hostProtoc} .nix-tools/protoc
        chmod +x .nix-tools/protoc
        export HAVEN_PROTOC="$PWD/.nix-tools/protoc"
        python3 - <<'PY'
from pathlib import Path
path = Path("mosh-kotlin/build.gradle.kts")
text = path.read_text()
old = """protobuf {
    protoc {
        artifact = \"com.google.protobuf:protoc:4.29.3\"
    }
"""
new = """protobuf {
    protoc {
        val localProtoc = System.getenv(\"HAVEN_PROTOC\")
        if (localProtoc != null) {
            path = localProtoc
        } else {
            artifact = \"com.google.protobuf:protoc:4.29.3\"
        }
    }
"""
if old not in text:
    raise SystemExit("Failed to patch mosh-kotlin/build.gradle.kts for local protoc")
path.write_text(text.replace(old, new))
PY

        mkdir -p nix-python-wheels
        ln -sf ${pycparserWheel} nix-python-wheels/pycparser-3.0-py3-none-any.whl
        ln -sf ${pyserialWheel} nix-python-wheels/pyserial-3.5-py2.py3-none-any.whl
        ln -sf ${rnsWheel} nix-python-wheels/rns-1.1.4-py3-none-any.whl
        ln -sf ${rnshWheel} nix-python-wheels/rnsh-0.1.7-py3-none-any.whl
        ln -sf ${cryptographyArm64Wheel} nix-python-wheels/cryptography-42.0.8-0-cp313-cp313-android_24_arm64_v8a.whl
        ln -sf ${cryptographyX64Wheel} nix-python-wheels/cryptography-42.0.8-0-cp313-cp313-android_24_x86_64.whl
        ln -sf ${cffiArm64Wheel} nix-python-wheels/cffi-1.17.1-0-cp313-cp313-android_24_arm64_v8a.whl
        ln -sf ${cffiX64Wheel} nix-python-wheels/cffi-1.17.1-0-cp313-cp313-android_24_x86_64.whl
        ln -sf ${chaquopyLibffiArm64Wheel} nix-python-wheels/chaquopy_libffi-3.3-3-py3-none-android_24_arm64_v8a.whl
        ln -sf ${chaquopyLibffiX64Wheel} nix-python-wheels/chaquopy_libffi-3.3-3-py3-none-android_24_x86_64.whl
        export HAVEN_PYTHON_WHEEL_DIR="$PWD/nix-python-wheels"
        export HAVEN_PYCPARSER_WHEEL="$PWD/nix-python-wheels/pycparser-3.0-py3-none-any.whl"
        export HAVEN_PYSERIAL_WHEEL="$PWD/nix-python-wheels/pyserial-3.5-py2.py3-none-any.whl"
        export HAVEN_RNS_WHEEL="$PWD/nix-python-wheels/rns-1.1.4-py3-none-any.whl"
        export HAVEN_RNSH_WHEEL="$PWD/nix-python-wheels/rnsh-0.1.7-py3-none-any.whl"

        cp ${./haven-release.jks} haven-release.jks
        export KEYSTORE_PASSWORD=android
        export KEY_ALIAS=release
        export KEY_PASSWORD=android
      '';

      installPhase = ''
        runHook preInstall

        mkdir -p "$out"
        cp app/build/outputs/apk/arm64/release/haven-*-arm64-release.apk "$out/"
        cp app/build/outputs/apk/x64/release/haven-*-x64-release.apk "$out/"

        mkdir -p "$out/nix-support"
        for apk in "$out"/*.apk; do
          echo "file binary-dist $apk" >> "$out/nix-support/hydra-build-products"
        done

        runHook postInstall
      '';
    };

    gradleDeps = gradle.fetchDeps {
      pkg = apk;
      attrPath = null;
      data = ./gradle-deps.json;
    };
  };

  gradleDepsUpdateScript = writeShellScriptBin "update-gradle-deps" ''
    set -euo pipefail

    ${apkAndDeps.gradleDeps.passthru.updateScript}

    deps_file="nix/gradle-deps.json"
    tmp_file="$(mktemp)"

    ${jq}/bin/jq '
      del(."https://maven.google.com")
      | del(."https://dl.google.com"["play-sdk/index/snapshot"])
    ' "$deps_file" > "$tmp_file"
    mv "$tmp_file" "$deps_file"
  '';

in
{
  inherit (apkAndDeps)
    apk
    gradleDeps
    ;

  inherit gradleDepsUpdateScript;

  shell = mkShell {
    packages = [
      jdk21_headless
      gradle
      androidSdk
      sdkmanager
      cmake
      python313
      cacert
    ];

    shellHook = ''
      export JAVA_HOME="${jdk21_headless}"
      export ANDROID_HOME="${androidHome}"
      export ANDROID_SDK_ROOT="$ANDROID_HOME"
      export ANDROID_NDK_HOME="${androidNdk}"
      export ANDROID_NDK="$ANDROID_NDK_HOME"
      export GRADLE_OPTS="-Dorg.gradle.java.home=$JAVA_HOME ''${GRADLE_OPTS-}"
      export SSL_CERT_FILE="${cacert}/etc/ssl/certs/ca-bundle.crt"
      export NIX_SSL_CERT_FILE="$SSL_CERT_FILE"
      export REQUESTS_CA_BUNDLE="$SSL_CERT_FILE"
      export PIP_CERT="$SSL_CERT_FILE"
      export PIP_TRUSTED_HOST="pypi.org files.pythonhosted.org chaquo.com"
      export HAVEN_CA_BUNDLE="$SSL_CERT_FILE"
      export HAVEN_PIP_TRUSTED_HOSTS="pypi.org,files.pythonhosted.org,chaquo.com"
      mkdir -p .nix-tools
      rm -f .nix-tools/protoc
      cp ${hostProtoc} .nix-tools/protoc
      chmod +x .nix-tools/protoc
      export HAVEN_PROTOC="$PWD/.nix-tools/protoc"
      mkdir -p .nix-python-wheels
      ln -sf ${pycparserWheel} .nix-python-wheels/pycparser-3.0-py3-none-any.whl
      ln -sf ${pyserialWheel} .nix-python-wheels/pyserial-3.5-py2.py3-none-any.whl
      ln -sf ${rnsWheel} .nix-python-wheels/rns-1.1.4-py3-none-any.whl
      ln -sf ${rnshWheel} .nix-python-wheels/rnsh-0.1.7-py3-none-any.whl
      ln -sf ${cryptographyArm64Wheel} .nix-python-wheels/cryptography-42.0.8-0-cp313-cp313-android_24_arm64_v8a.whl
      ln -sf ${cryptographyX64Wheel} .nix-python-wheels/cryptography-42.0.8-0-cp313-cp313-android_24_x86_64.whl
      ln -sf ${cffiArm64Wheel} .nix-python-wheels/cffi-1.17.1-0-cp313-cp313-android_24_arm64_v8a.whl
      ln -sf ${cffiX64Wheel} .nix-python-wheels/cffi-1.17.1-0-cp313-cp313-android_24_x86_64.whl
      ln -sf ${chaquopyLibffiArm64Wheel} .nix-python-wheels/chaquopy_libffi-3.3-3-py3-none-android_24_arm64_v8a.whl
      ln -sf ${chaquopyLibffiX64Wheel} .nix-python-wheels/chaquopy_libffi-3.3-3-py3-none-android_24_x86_64.whl
      export HAVEN_PYTHON_WHEEL_DIR="$PWD/.nix-python-wheels"
      export HAVEN_PYCPARSER_WHEEL="$PWD/.nix-python-wheels/pycparser-3.0-py3-none-any.whl"
      export HAVEN_PYSERIAL_WHEEL="$PWD/.nix-python-wheels/pyserial-3.5-py2.py3-none-any.whl"
      export HAVEN_RNS_WHEEL="$PWD/.nix-python-wheels/rns-1.1.4-py3-none-any.whl"
      export HAVEN_RNSH_WHEEL="$PWD/.nix-python-wheels/rnsh-0.1.7-py3-none-any.whl"

      if [ ! -f local.properties ]; then
        printf '%s\n' \
          "sdk.dir=$ANDROID_HOME" \
          "ndk.dir=$ANDROID_NDK_HOME" \
          "cmake.dir=${androidHome}/cmake/3.31.6" \
          > local.properties
      fi

      mkdir -p "$HOME/.android"

      echo "Haven Nix dev shell ready"
      echo "  JAVA_HOME=$JAVA_HOME"
      echo "  ANDROID_HOME=$ANDROID_HOME"
    '';
  };
}

{
  description = "Reproducible Nix build for Haven Android app";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/release-25.11";
    flake-utils.url = "github:numtide/flake-utils";
    termlib-src = {
      url = "git+https://github.com/GlassOnTin/termlib.git?rev=b1df4c5f748ff5899078ef2797f515ffae729ddb";
      flake = false;
    };
    mosh-kotlin-src = {
      url = "git+https://github.com/GlassOnTin/ssp-transport.git?rev=dec26cae135f8ecf770379a9bfc053e23e60557d";
      flake = false;
    };
    et-kotlin-src = {
      url = "git+https://github.com/GlassOnTin/et-kotlin.git?rev=67a8e831642686590a8484bda2d6b4a1fd0ca288";
      flake = false;
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      termlib-src,
      mosh-kotlin-src,
      et-kotlin-src,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        app = pkgs.callPackage ./nix/haven.nix {
          termlibSrc = termlib-src;
          moshKotlinSrc = mosh-kotlin-src;
          etKotlinSrc = et-kotlin-src;
        };
      in
      {
        packages = {
          default = app.apk;
          apk = app.apk;
          gradleDeps = app.gradleDeps;
          gradleDepsUpdateScript = app.gradleDepsUpdateScript;
        };

        devShells.default = app.shell;
      }
    );
}

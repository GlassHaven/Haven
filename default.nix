let
  flake = builtins.getFlake ("git+file://" + toString ./.);
  system = builtins.currentSystem;
in
{
  inherit (flake.packages.${system})
    apk
    gradleDeps
    gradleDepsUpdateScript
    ;

  shell = flake.devShells.${system}.default;
}

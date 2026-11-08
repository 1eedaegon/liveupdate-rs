{
  description = "liveupdate-rs Android Rust development environment";

  inputs = {
    # This branch still supports Intel macOS, which is the current development host.
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-26.05-darwin";
    flake-utils.url = "github:numtide/flake-utils";
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { nixpkgs, flake-utils, rust-overlay, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        overlays = [ (import rust-overlay) ];
        pkgs = import nixpkgs { inherit system overlays; };
        rustToolchain = pkgs.rust-bin.stable."1.96.0".minimal.override {
          extensions = [ "clippy" "llvm-tools-preview" "rust-src" "rustfmt" ];
          targets = [
            # Galaxy S23 and current physical Android devices.
            "aarch64-linux-android"
            # Android Studio phone emulators on this Intel macOS host and CI.
            "x86_64-linux-android"
          ];
        };
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            cargo-llvm-cov
            cargo-ndk
            gradle
            jdk21
            rustToolchain
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk21}"

            if [ -z "''${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
              export ANDROID_HOME="$HOME/Library/Android/sdk"
            elif [ -z "''${ANDROID_HOME:-}" ] && [ -d "$HOME/Android/Sdk" ]; then
              export ANDROID_HOME="$HOME/Android/Sdk"
            fi

            if [ -n "''${ANDROID_HOME:-}" ]; then
              export ANDROID_SDK_ROOT="$ANDROID_HOME"
              export PATH="$ANDROID_HOME/platform-tools:$PATH"

              android_ndk="$ANDROID_HOME/ndk/27.3.13750724"
              if [ -d "$android_ndk" ]; then
                export ANDROID_NDK_HOME="$android_ndk"
                export ANDROID_NDK_ROOT="$android_ndk"
              fi
              unset android_ndk
            fi

            echo "liveupdate-rs dev shell: $(rustc --version) · $(cargo ndk --version)"
            echo "Run: ./scripts/check-android-env.sh"
          '';
        };
      });
}

#!/bin/sh
set -eu

packages=""
for argument in "$@"; do
    case "$argument" in
        --licenses)
            exit 0
            ;;
        --install|--channel=*)
            ;;
        --*)
            echo "unsupported sdkmanager option: $argument" >&2
            exit 2
            ;;
        *)
            package=$(printf '%s' "$argument" | tr ';' '/')
            packages="$packages $package"
            ;;
    esac
done

if [ -z "$packages" ]; then
    exit 0
fi

# Package names cannot contain spaces. Deliberate splitting passes each translated
# legacy package path as one Android CLI argument.
# shellcheck disable=SC2086
exec android --sdk="$ANDROID_SDK_ROOT" sdk install $packages

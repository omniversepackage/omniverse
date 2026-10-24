# Bundled audio fallback

Media3 1.11.1's FFmpeg audio renderer is built into the app with FFmpeg 6.0.1.
The platform renderer remains first, preserving working hardware decoding and HDMI
passthrough. When it cannot handle an audio format, FFmpeg decodes it locally to PCM.
No external app, proxy or server conversion is involved.

Enabled decoders: dca (DTS/DTS-HD), ac3, eac3, truehd, mlp, aac, aac_latm,
mp3, mp2, flac, alac, opus and vorbis. This is audio-only, without GPL or nonfree
components. `libffmpegJNI.so` is a separately replaceable shared library built for
armeabi-v7a, arm64-v8a, x86 and x86_64, API 23, with 16 KB ELF page alignment.

Rebuild on Linux with Android SDK NDK 26.1.10909125 and CMake 3.22.1:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk bash tools/build-audio-decoder.sh
```

The Java sources are from AndroidX Media3 tag 1.11.1, decoder_ffmpeg/src/main/java.
Only adaptation: FfmpegLibrary's Checker Framework MonotonicNonNull annotation is
replaced with AndroidX Nullable. JNI and FFmpeg sources are unmodified. R8 rules
must preserve the native methods, class names, and growOutputBuffer callback.

Licenses and attribution ship in `src/main/assets/licenses`. Distribute the
corresponding source bundle/build script with APKs containing this library.
The LGPL permits debugging modifications to the library; users can rebuild the
shared library, replace the APK's lib/<ABI>/libffmpegJNI.so and re-sign the APK.
A differently signed APK needs a separate install; export app settings first.

Validation: FfmpegAudioDeviceTest checks decoder availability, non-silent six-channel
DTS PCM output and a real DTS-HD MA MKV playing with the platform audio renderer
entirely excluded. Run on both 64-bit and forced 32-bit processes. A remote Onn box
was not available, so its final HDMI/audio configuration still requires user testing.

Audio decoder fixtures (test APK only):

- `tone.dts`: generated 440 Hz sine, 48 kHz, six channels; two 1884-byte DTS frames.
  Generated with `ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -ac 6 -c:a dca -strict -2 -frames:a 1 -f dts tone.dts`.
- `dts-hd-ma.mkv`: AndroidX Media3 1.11.1 test asset
  `libraries/test_data/src/test/assets/media/mkv/sample_with_dts_hd_ma.mkv`.
  Source: https://github.com/androidx/media/tree/1.11.1/libraries/test_data
  Android Open Source Project, Apache 2.0; license bundled in main/assets/licenses.

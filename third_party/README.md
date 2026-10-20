# Third-party source

## libgocryptfs

- Source: https://github.com/hardcore-sushi/libgocryptfs
- Pinned source commit: `4cf68bded7cfd6cce646516e059b10561589ad2d`
- License: MIT, copyright Jakob Unterwurzacher; license preserved in `libgocryptfs/LICENSE` and file headers.
- Purpose: standard gocryptfs password handling, encrypted filenames, authenticated file content encryption, random-access reads and writes.
- Local build adapter: `internal/stupidgcm/without_openssl.go` disables the optional OpenSSL backends. Encryption uses the upstream Go `crypto/aes`, `crypto/cipher` and `golang.org/x/crypto` implementations. No on-disk encryption format changes.
- Build: `scripts/build-vault.sh`, invoked by Gradle. Requires Go and the configured Android NDK. Binaries are generated locally and ignored by Git.

The FileWeave JNI adapter in `app/src/main/jni/vault.c` is original glue for this MIT library. No DroidFS AGPL application code was copied. The DroidFS project informed the evaluation and the choice of the library, and is acknowledged at https://github.com/hardcore-sushi/DroidFS.

Media playback uses AndroidX Media3 (Apache-2.0); bridge networking uses OkHttp (Apache-2.0) through its Media3 adapter. Mac HTTPS transport uses the Python standard library and a locally generated certificate.

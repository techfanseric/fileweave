//go:build without_openssl

// Build adapter: selects upstream Go crypto backends instead of OpenSSL.
package stupidgcm

import "crypto/cipher"

const BuiltWithoutOpenssl = true
func NewAES256GCM(key []byte) cipher.AEAD { panic("OpenSSL backend disabled") }
func NewXchacha20poly1305(key []byte) cipher.AEAD { panic("OpenSSL backend disabled") }

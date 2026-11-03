# Android attestation trust anchors

`google-attestation-roots.pem` contains the two public PEM root certificates from
[Google's attestation root endpoint](https://android.googleapis.com/attestation/root),
retrieved on 2026-10-22. Both certificates are preserved as published.

The roots are documented by
[Android Developers](https://developer.android.com/privacy-and-security/security-key-attestation#root_certificates).
The configured file provides trust anchors independently of a client-supplied chain.

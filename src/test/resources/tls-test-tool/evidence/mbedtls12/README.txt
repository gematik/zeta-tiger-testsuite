TLS 1.2 mbed TLS parser fixtures from the bundled TLS Test Tool 1.0.1.
Binary SHA-256: ef649825e7bcf526400d19aa2bc10fbc21bf52b0fe1e45a8c4bf0d3a940c84a6
Generated using the TLS service REST API and independent Python ssl peers.
All peer certificates use EC P-256 and ECDSA; positive connections negotiated
TLSv1.2 with ECDHE-ECDSA-AES128-GCM-SHA256.
Logs retain actual certificate records, decrypted HTTP data and relevant protocol events.
Other debug data and session secrets were removed. Private keys are not included.
The certificate validity dates are irrelevant to parsing these recorded messages.
The foreign-server and wrong-host peers closed before the tool consumed their alert;
these fixtures consequently have no certificate-validation cause in the server log.

<img align="right" width="250" height="47" src="docs/img/Gematik_Logo_Flag.png"/> <br/>

# Release Notes ZETA Tiger Testsuite

## Version: 0.2.4

### changed
- Updates to documentation and clean up of configuration files

## Version: 0.2.3

This version tests the "happy flow" for the ZETA protocol between the ZETA client SDK and the ZETA Guard.

Therefore, the stable API of the ZETA client SDK, the ZETA-Guard and the network protocol is tested (with comments see below).
Not all validations are tested yet and will follow in later releases.

#### Test Focus:

- Discovery of server parameters via .well-known files
- Nonce Endpoint
- Software-based Client Attestation
- DPoP token generation
- Client Registration
- SM(C)-B Token use
- Client Assertion
- Access Token handling
- Web Sockets (broken)
- PoPP Token Validation
- Policy Decision


### Known issues:

- Tiger Testsuite and Standalone Tiger Proxy (TestProxy) communication is unreliable, therefor many tests fail most of the time.
- Tiger Local Proxy does not support Websockets, therefore the websocket tests fail.

#### Limitations

- Telemetrie


## Release 0.2.2

This version tests the "happy flow" for the ZETA protocol between the ZETA client SDK and the ZETA Guard.

Therefore, the stable API of the ZETA client SDK, the ZETA-Guard and the network protocol is tested (with comments see below).
Not all validations are tested yet and will follow in later releases.

#### Test Focus:

- Discovery of server parameters via .well-known files
- Nonce Endpoint
- Software-based Client Attestation
- DPoP token generation
- Client Registration
- SM(C)-B Token use
- Client Assertion
- Access Token handling
- Web Sockets (broken)


### Known issues:

- Tiger Testsuite and Standalone Tiger Proxy (TestProxy) communication is unreliable, therefor many tests fail most of the time.
- Tiger Local Proxy does not support Websockets, tehrefore the websocket tests fail.

#### Limitations

- PoPP Token Validation
- Policy Decision


## Release 0.1.3

### added:
- Prototype of the ZETA Tiger Testsuite
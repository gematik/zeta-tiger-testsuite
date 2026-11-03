# Push Notification OpenAPI provenance

The OpenAPI files and shared definitions in this directory are unchanged copies from the gematik Push Notification Concept repository.
The original directory structure and relative `$ref` references are preserved for local resolution.

- Repository: [gematik/gem-push-notifications-concept](https://github.com/gematik/gem-push-notifications-concept)
- Source ref: `1.1`
- Pinned commit: [`bdd4f3553993f90d025d167818a32a18fc70ab98`](https://github.com/gematik/gem-push-notifications-concept/tree/bdd4f3553993f90d025d167818a32a18fc70ab98/docs_sources)
- Source directory: `docs_sources`
- Local directory: `src/test/resources/schemas/push_notifications_openapi`
- API version: `1.1.0`
- Imported: 2026-09-09
- License: Apache License 2.0, reproduced unchanged in `LICENSE`; copyright 2025 gematik GmbH.

## Included definitions

- `fd_openapi.yaml`: Client–FD Push API, OpenAPI 3.0.3, including `GET /pushers`, pusher management, channels, and history.
- `push_gateway_openapi.yaml`: Push Gateway API, OpenAPI 3.1.0.
- `definitions/*.yaml`: All eight shared definitions referenced by the Client–FD API.

The response schema for `GET /pushers` is `definitions/pusher_get.yaml#/schema`.
That file also contains examples, so its root must not be treated as the response schema.
The schema permits populated lists and does not itself require the `pushers` property or an object at the root.
Scenarios establishing an empty pusher list must additionally require an object containing a present, empty `pushers` array.

## Specification reference

The maintained ZETA baseline is `docs/specifications/2.0.0-cc/gemSpec_ZETA_V2.0.0_CC.html`, gemSpec ZETA V2.0.0 CC, revision 1658346 dated 09.07.2026, status "zur Abstimmung freigegeben", classification "öffentlich_Entwurf".
A_29972 references the client-facing interfaces from `gemF_PushNotification`, including `GET /pushers` and A_27104.
The ZETA bibliography links to the mutable [`gemF_PushNotification/latest`](https://gemspec.gematik.de/docs/gemF/gemF_PushNotification/latest/) reference.
At import time its bibliography references the `1.1` Push Notification Concept documentation as `OpenApi_Notification_Fachdienst` and `OpenApi_Notification_PushGateway`.
The imported commit pins that external source independently of the ZETA draft baseline; it does not establish which revision the mutable reference resolved to when the draft was issued.
The import does not add requirement or TestAspect coverage.

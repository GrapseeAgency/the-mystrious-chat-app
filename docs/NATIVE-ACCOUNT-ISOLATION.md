# Android account storage and invocation boundary

`AccountRepositories` now creates a complete repository per `(normalized gateway, viewer ID)`: a hashed Room filename, a separate preferences DataStore, immutable DAOs and a transport pinned to that gateway and credential. It does not merely change a singleton database filename while retaining the old DAOs.

`AccountRepositoryRouter` is the stable Hilt-facing handle. It captures one concrete repository for the lifetime of each invocation, including suspended calls. Flow observers cancel/rebind on a switch and clear their previously rendered values first. Late database responses stay in the originating namespace. Retired transports refuse new API requests; they cannot read a newly selected gateway/token from mutable globals. Release R8 rules preserve the interface ABI used at this boundary.

- Guest/onboarding message storage is in-memory, not the prior account's database.
- Old `pulse.db` data is retained untouched. Ownership is not inferred from the currently selected user; no automatic legacy queue migration or destructive clearing is performed.
- Returning to an account/gateway reopens its own queue, drafts, messages, stories, call-log cache, saved items and metadata.
- The authenticated Compose subtree is keyed to account/gateway so remembered room navigation does not carry into another identity. Call overlays terminate media on disposal.
- Changing gateway signs out and clears the prior server credential instead of sending it to the new origin. Users must authenticate on the new server.

Tests cover namespace normalization/separation, observer switching, a suspended invocation across an account switch, and real Room draft isolation on device. Their execution status must be checked against the exact candidate in CI; source changes alone are not certification.

## Remaining boundaries

This is not an account recovery/authorization fix. The server still needs secure issuance/recovery and complete actor binding. Tasks that have not yet invoked the repository at the time of an account switch still require cancellation at their UI/feature owner; the router cannot infer which account a future call was originally intended for. Also review notification origin/account routing and all non-repository media/feature state. Immutable repository ownership protects in-flight storage access, not arbitrary external mutable state.

Database/DataStore handles are retained while captured continuations may still use them. They are reused on returning to an account, rather than closed underneath an active transaction. No cross-account import is performed.

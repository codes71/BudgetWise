# Offline sign-in and several accounts on one phone

Status: spec, not built. The design canvas gets amended first, then the Android app.
Source: the user's request of 2026-10-26.

## Goals

1. No server line anywhere. The "Server: http://..." line goes from the sign-in screen and Settings. The sync button stays where it already is.
2. Signing in depends on the connection. A new account needs the internet. An account already used on this phone can sign in offline.
3. Entries are never deleted when someone switches account or signs out. Each account's entries stay on the phone until they reach the server.
4. Sync happens in the background for every account on the phone. When a person signs in online, they see all of their own data.

## Sign-in rules

| Situation | Result |
|---|---|
| Online, right password | Signed in. Their full data is pulled from the server. |
| Online, wrong password | "Wrong email or password." |
| Offline (or server unreachable), account used on this phone before, right password | Signed in from the phone. Their entries on the phone show. New entries save and sync later. |
| Offline, account used on this phone before, wrong password | "Wrong email or password." |
| Offline, account never used on this phone | Refused: "Connect to the internet to sign in to this account for the first time." |
| Switching from account A (with unsynced entries) to account B | No prompt. A's entries stay on the phone and upload in the background. |
| Sign out with unsynced entries | No prompt. The entries stay and upload in the background. |

"Server unreachable" means the sign-in request failed to connect or timed out. A 401 from the server is a wrong password, never a reason to fall back to offline sign-in.

## Sync rules

- Active account: push, then pull, as today (at launch, after a save, on reconnect, on pull to refresh).
- Other accounts on the phone: push only, each with its own saved session, while that session is still valid.
- An inactive account whose session has expired: its entries wait on the phone and upload the next time that account signs in.
- After an offline sign-in, the typed password is held in memory only, until the app first reaches the server. Then it is exchanged for a fresh session, and the password is dropped. If the app closes before that, the existing "Sign in again to sync" state shows. Entries stay safe either way.
- First online sign-in of an account on this phone: a full pull for that account.

## Data on the phone

- Every entry, outbox item, cached budget and pull cursor belongs to one account (owner = user id). Screens show only the active account's data.
- Database migration: existing rows go to the account in the saved session. If there is no saved session, they go to the next account that signs in online.
- Saved per account, in the existing Keystore-encrypted store:
  - the session (token, expiry, user), as today;
  - an offline password check: a salted PBKDF2-SHA256 hash. The password itself is never stored.
- Sign-out drops the account's session once its entries have uploaded. The offline password check stays, so offline sign-in keeps working for that account.

## Screens to change (design canvas first, then the app)

- Sign in: remove the server line. Add "No connection, account used on this phone" (signs in, with a short note that entries sync when back online). Change "No connection" for a new account to the refusal message above.
- Remove the board "Different account, unsynced entries" and its dialog.
- Remove the board "Sign out, unsynced entries" and its dialog, since nothing is lost on sign-out any more.
- Settings: remove the server line. Keep the sync button and its states.
- Burmese versions of every new or changed string.

## Not changing

- The server API. POST /auth/token and the transaction endpoints stay as they are. Ids stay opaque strings.
- The Firestore move (feat/firestore) is separate work. It includes the one-time copy of the old MongoDB data the user asked for.

## Rulings to confirm with the user

1. The sign-out warning dialog goes too, because sign-out no longer loses entries.
2. A server that cannot be reached counts as "offline" for sign-in.
3. Rows with no known owner go to the next account that signs in online.

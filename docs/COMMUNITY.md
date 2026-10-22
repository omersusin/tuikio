# Community submissions

People can suggest sources ("this repo has terminal games"), publish their own games, and
send corrections from the app. Everything becomes a pull request against the catalog
repository that humans review. Nothing a user submits is installed or executed by the app:
an accepted game still has to be ported, built in an isolated worker and tested before it can
reach `STORE_READY`.

## Flow

1. **My Sources (on the phone).** Pasting a link validates it locally (`SourceUrl`: https
   only, no IP literals, credentials or unusual ports, no intranet names). It is stored only on
   that phone as `PERSONAL_SOURCE`.
2. **Suggest to community.** The user picks a credit:
   - *Anonymous*: credited as "Anonymous community contributor", no account involved.
   - *GitHub*: signs in through the browser (OAuth with PKCE). The server confirms the login
     before crediting it.

   The app shows exactly what will become public, then:
   - asks the server for a challenge and solves a hashcash proof of work (a fraction of a
     second; spam gets expensive without CAPTCHAs or trackers);
   - submits, and receives a receipt (`TK-XXXX-XXXX`; the full 16-character secret is what
     authorizes status checks).
3. **Review bot.** The server's GitHub App opens one PR per submission that adds
   `community/submissions/<date>-<id>.json`. User text is fenced in the PR body, so it cannot
   inject markdown, links or @mentions. Verified GitHub contributors are added to the commit as
   `Co-authored-by`, so the contribution shows on their profile.
4. **Review outcome.** Webhooks move the receipt's state. A merged PR becomes
   `APPROVED_SOURCE`, a PR closed without merging becomes `REJECTED`, and the labels
   `changes-requested`, `compatibility-testing`, `store-ready` and `delisted` map to their
   states. The app polls the receipt when the user opens My Sources.

**Publish a game** works the same way with `kind: game` and a description draft (title,
summary, category, SPDX license, controls, ownership). "Official upload" requires GitHub
sign-in, and the server checks that the account can administer the repository. Otherwise the
game is listed as a community port that credits the original authors first.

## Server API (`backend/`)

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/challenge` | `{challenge, bits, expires_at}`. Challenges are HMAC-signed, expire in 10 min and can be used once |
| POST | `/v1/submissions` | JSON `SubmissionRequest`. Attributed requests carry `Authorization: Bearer <github token>`, used only for verification and never stored. `201 {receipt_secret, status}`, `409` duplicate, `422` invalid, `403` failed proof or credit check |
| GET | `/v1/submissions/{secret}` | `{receipt, state, pr_url, reason}` |
| POST | `/v1/github/token` | `{code, code_verifier, redirect_uri}` → `{access_token}`. The OAuth client secret stays on the server |
| POST | `/v1/github/webhook` | `X-Hub-Signature-256` HMAC required |
| GET | `/v1/preview?url=` | Title, description and license for a link. GitHub links use the API. Other sites go through the SSRF-safe fetcher (public addresses only, DNS pinned, TLS verified, redirects re-checked, 256 KB cap) |
| GET | `/healthz` | |

Rate limits are per client per hour: 60 challenges, 6 submissions, 240 status checks, 20 token
exchanges and 60 previews. Bodies are capped at 64 KB.

## Running the server

It is a single JVM process with no database: JSON files under `TUIKIO_DATA_DIR`. Bind it to
localhost behind a TLS-terminating reverse proxy.

```sh
./gradlew :backend:installDist
TUIKIO_DATA_DIR=/var/lib/tuikio \
TUIKIO_BIND=127.0.0.1 TUIKIO_PORT=8080 TUIKIO_TRUST_FORWARDED_FOR=1 \
TUIKIO_CATALOG_REPO=omersusin/tuikio-catalog \
TUIKIO_GITHUB_APP_ID=123456 TUIKIO_GITHUB_APP_INSTALLATION_ID=7890123 \
TUIKIO_GITHUB_APP_PRIVATE_KEY_FILE=/etc/tuikio/app-key.pem \
TUIKIO_GITHUB_CLIENT_ID=Iv1.xxxx TUIKIO_GITHUB_CLIENT_SECRET_FILE=/etc/tuikio/oauth-secret \
TUIKIO_WEBHOOK_SECRET_FILE=/etc/tuikio/webhook-secret \
backend/build/install/backend/bin/backend
```

Secrets are only read from files (`*_FILE`), never from arguments, so they stay out of process
listings and shell history. The App's private key must be PKCS#8
(`openssl pkcs8 -topk8 -nocrypt -in github.pem -out app-key.pem`). Without the App settings,
submissions are stored and queued, and the bot retries every 10 minutes once it is configured.

### GitHub setup (needs the repository owner)

1. **GitHub App** on the catalog repository, with Contents: read & write, Pull requests:
   read & write and Metadata: read. Subscribe to *Pull request* events. Set the webhook URL to
   `https://<server>/v1/github/webhook` with a random secret. Install it on the catalog
   repository only.
2. **OAuth credentials for sign-in.** Use the same App's user-authorization settings or a
   separate OAuth App, with callback URL `dev.tuikio.app://oauth/callback`. The client ID goes
   into the app's `assets/config.json`. The client secret goes only to the server.
3. App configuration, `app/src/main/assets/config.json` (all fields optional; missing ones
   turn the related features off with an explanation in the UI):

   ```json
   {
     "submissions_url": "https://submit.example.org",
     "github_client_id": "Iv1.xxxx",
     "catalog_url": "https://example.org/catalog/catalog.json",
     "catalog_public_key": "-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----"
   }
   ```

No token that can write to the catalog ever ships in the APK. The app holds only the user's
own GitHub token, and the server only uses it to confirm who is asking.

## Tests

`backend/src/test` runs the real server against an in-process fake GitHub:
- anonymous and credited submissions;
- forged, replayed and expired proofs;
- duplicates and invalid input;
- rate limits;
- PR retry after failures;
- token exchange, webhook signatures and state changes;
- SSRF cases: private, mapped and NAT64 addresses, DNS rebinding, redirects to private
  hosts, size caps.

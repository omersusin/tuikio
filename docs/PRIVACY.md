# Privacy

Tuikio is a game launcher. It does not need to know who you are, and by default it doesn't.

## What stays on your phone

- Your library, game saves, settings, brand choices and custom logo.
- **My Sources**: links you saved. They leave the phone only when you tap "Suggest to
  community" and confirm the preview of what will be published.
- A custom logo is decoded, cropped and re-encoded on the phone, which drops EXIF and location
  metadata. It is never uploaded.

## When the app goes online

Only when you ask:

| Action | Contacts | Sends |
|---|---|---|
| Refresh catalog | the catalog URL in the build's config | an ordinary HTTPS request (with `If-None-Match`) |
| Preview a source link | Tuikio's server, or GitHub's API | the link |
| Suggest a source / publish a game | Tuikio's submission server | what the preview showed: link, title, note, credit, and for games the description |
| Check a suggestion's status | Tuikio's submission server | the receipt secret |
| Sign in to GitHub | github.com in your browser, then Tuikio's server | the one-time code and PKCE verifier |

There are no analytics, advertising or crash-reporting SDKs, and no background network
activity. Games marked `network: none` (all current games) never use the network.

## Anonymous suggestions

They carry no name, account or device identifier. The public pull request credits
"Anonymous community contributor". While handling a request, the server sees your network
address, as every server does. It uses that only to compute a pseudonym for rate limiting:
an HMAC with a key that is random per server start and mixed with the date, kept in memory.
Neither the address nor the pseudonym is written to disk or logs.

The receipt (`TK-XXXX-XXXX`) is random and not linked to you. The server stores only a
SHA-256 hash of its secret.

## GitHub sign-in (optional)

- OAuth with PKCE in your browser. Tuikio never sees your password.
- The requested scope is `public_repo`. The app uses the token to read your login and to check
  repository admin rights when you publish a game as its owner.
- The token is encrypted with an AES-GCM key held in Android Keystore. It is excluded from
  backups and device transfers. "Disconnect" deletes both the token and the key.
- On a credited submission, the app sends the token to Tuikio's server once, so the server
  can confirm the login (and ownership). The server does not store or log it. Your login then
  appears publicly as the pull request's credit and commit co-author.

## Server data

For each submission the server keeps: the public fields you sent, the review state, the pull
request link, timestamps and the receipt hash. It keeps no IP addresses, tokens, device
identifiers or receipt secrets. Records are plain JSON files that the operator can delete on
request. Contact details belong in the deployment's own privacy notice.

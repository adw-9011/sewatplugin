# SweatyRunes resource pack

`SweatyRunes-pack.zip` supplies the custom Rune Table model used by the placed table and the
Rune Table item. It contains no rune textures: rune heads are plain player heads skinned from
Mojang texture hashes.

The SHA-1 of the bundled zip is:

```
bff291fcf1e314552a58f18975b9a7d6530ba3ad
```

## Hosting it

1. Upload `SweatyRunes-pack.zip` somewhere the server can serve over HTTP(S) with a **direct
   download link** (a GitHub release asset, your own web host, S3, ...).
2. Set the URL and SHA-1 in `plugins/Sweats/config.yml`:

   ```yaml
   runes:
     resource-pack:
       enabled: true
       url: "https://example.com/SweatyRunes-pack.zip"
       sha1: "bff291fcf1e314552a58f18975b9a7d6530ba3ad"
       required: false
       prompt: "SweatyRunes needs its resource pack for custom models."
   ```

3. Run `/sweats reload`. Existing players receive the pack the next time they join.

If you edit the pack, recompute the SHA-1 (`sha1sum SweatyRunes-pack.zip` on Linux/macOS, or
`Get-FileHash SweatyRunes-pack.zip -Algorithm SHA1` in PowerShell) and update `sha1` too.
While either `url` or `sha1` is empty, Sweats logs one warning and does not send the pack.

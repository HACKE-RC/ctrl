# Privacy

This app runs a local HTTP server on your Android device.

## Data the app can access

Depending on what you grant, the app may be able to access:

- Screen content (via MediaProjection) to produce screenshots.
- UI structure, view text, and the current foreground app (via Accessibility).
- Installed and launchable app package names (via package manager; requires `QUERY_ALL_PACKAGES`).

## Where data goes

- The app does not include analytics or crash reporting.
- The app does not send data to any third-party service by itself.
- Screenshots/UI data are only returned to the client that can reach the MCP endpoint you expose.

Because this is a network-exposed control surface, you are responsible for restricting access (see `README.md`).

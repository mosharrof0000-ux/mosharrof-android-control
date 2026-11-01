# Device agent (planned)

The device agent will expose typed, least-privilege operations to the authenticated relay. Initial target operations:

- `device.get_diagnostics` — read-only storage and memory summary.
- `files.list_selected_tree` — list only a folder explicitly granted through SAF.
- `media.scan_duplicates` — hash and report candidates; never delete.
- `files.quarantine` — reversible move after preview and confirmation.

No arbitrary shell command is accepted. Termux support, if added, will use a separate allowlist and explicit user enablement.

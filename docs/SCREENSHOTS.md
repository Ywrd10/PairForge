# Portfolio screenshots

No presentation screenshots are tracked. Automated browser captures are disabled
to keep credentials and invitation tokens out of artifacts. The architecture
diagram and measured evidence in the README are available now; the following
three optional real UI captures can be added after the owner supplies them.
No AWS restart is needed for this documentation milestone.

Use the local setup or the next separately scheduled demo. Capture actual UI;
do not simulate outcomes. Use two independently authenticated sessions and harmless
standard-library examples. Before sharing, crop or redact email addresses,
password-manager overlays, account/room/execution identifiers, invitation tokens,
browser address bars, unrelated tabs, and any private source/output. Redaction
must not change code, status, output or connection-state claims. PNG is sufficient.

| Proposed file under `docs/assets/` | What to capture | What it demonstrates |
| --- | --- | --- |
| `collaboration.png` | Two views of the same Monaco room, showing a comment copied by a real shared edit and both connection-status panels | Shared code and active authenticated collaboration |
| `execution.png` | Editor language and actual source above a SUCCEEDED output panel, with stdout, empty stderr and exit code zero visible | A real Java or Python execution result; crop/redact its identifier |
| `dashboard.png` | Your workspace, a demo room, Create a room, and empty Room ID to join / Invitation token to join fields | The create/open/join workflow without revealing an invitation |

For an execution example, use a program that prints `PairForge demo OK` and
capture the matching actual result. For collaboration, have one session add a
comment and verify it appears in the other before capturing; a static screenshot
supports the UI presentation but does not replace the existing two-person and
benchmark evidence.

Review the final pixels before committing, add brief descriptive alt text, and
reference only files that actually exist. Do not add broken image placeholders,
stock imagery, invented metrics or unrelated badges. The capture filenames above
are a checklist, not claims that these assets have already been produced.

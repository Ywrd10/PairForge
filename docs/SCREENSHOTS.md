# Final portfolio screenshots

Three real deployed Chrome/PC captures were supplied by the owner on 2026-09-28
and prepared with explicitly approved non-generative cropping and opaque
redaction. They are linked near the README introduction:

| Asset | Actual view |
| --- | --- |
| [dashboard.png](assets/dashboard.png) | Room list, creation form and empty invitation join fields |
| [collaboration.png](assets/collaboration.png) | One shared Monaco editor with Python selected and the harmless demo source |
| [execution.png](assets/execution.png) | Connected room, SUCCEEDED Python execution, exit code 0, 655 ms and `PairForge demo OK` stdout |

Browser bars and the room-details card were cropped out. Both execution-ID
occurrences were covered with solid, opaque redactions. The original UI, source,
status, duration and output were preserved; no mock UI or generated content was
used. Only the sanitized PNGs belong in Git, not the original clipboard captures.
The editor image shows one view and is not independent proof of a two-user
interaction. Existing collaboration and beta evidence remains documented
separately.

Visual privacy review found no emails, UUIDs, invitation/session tokens, private
source, AWS identifiers or password-manager overlays in the final images. PNGs
were exported onto fresh canvases to discard the original metadata. Automated
browser captures remain disabled. Use the workflow below for future recaptures.

## Prepare locally

1. Follow [local development](LOCAL_DEVELOPMENT.md) or the README's
   [Running locally](../README.md#running-locally) steps to run the complete local
   API, frontend, dependencies and sandbox worker. This does not require AWS.
   Do not rerun `-Initialize` if your local `.env` already exists.
2. Open `http://127.0.0.1:5173` in two independent browser sessions, such as two
   Chrome profiles or a normal window and an Incognito window. Register/log in
   privately with separate accounts. Never send passwords or session tokens.
3. Use a neutral room name such as **PairForge demo**. Create it in the first
   session, choose **Copy invitation**, share it privately and join from the
   second session. Save the invitation before leaving; it is not recoverable
   after reload/logout. Do not include it in a capture.
4. Use a desktop viewport, legible text and consistent zoom. Close unrelated tabs,
   developer tools, notifications and password-manager prompts. Move the pointer
   off room links so the browser does not display a UUID-containing hover URL.

## 1. Dashboard — `dashboard.png`

1. Return to the first session's dashboard. If the newly created invitation is
   still displayed, save it privately and choose **I saved the invitation — create
   another room** to restore the clean creation form.
2. Show **Your workspace**, the neutral demo room in **Your rooms**, **Create a
   room** with its language selector, and **Have an invitation?** with **Room ID
   to join** and **Invitation token to join** both empty.
3. Capture the app content, with no open dropdown or autofill suggestion.

**Hide first:** the account email in the top navigation, browser address bar and
hover URLs, any personal room names, and any created-room UUID/invitation panel.
Clear the join fields rather than photographing a real invitation. Use an opaque
redaction only where cropping cannot preserve the useful view.

## 2. Collaborative editor — `collaboration.png`

1. Open the shared room in both sessions. Choose **Python** in **Editor language**
   and replace the entire starter source with this harmless example:

   ```python
   print("PairForge demo OK")
   ```

2. In the other session, add `# Shared edit from teammate` above the print line.
   Wait until the same source appears in both editors and both **Connection
   status** panels show **Connected — synchronized in Redis.**
3. Capture both real views side by side, or supply two matching cropped captures
   for assembly into one image. Keep Monaco, **Editor language**, **Run**, the
   shared comment and both connection-status panels legible. A screenshot shows
   the synchronized UI; it does not independently prove the interaction history.

**Hide first:** both account emails, address bars, the room-details UUID, the
owner's invitation panel, any output/history execution IDs, and private code.
Scroll/crop below the room-details card where practical. Include only the harmless
example; do not invent a participant indicator that the UI does not display.

## 3. Successful execution — `execution.png`

1. Use the same real room, with **Python** selected and the source above (the
   shared comment may remain). Alternatively use this valid **Java** example:

   ```java
   public class Main {
       public static void main(String[] args) {
           System.out.println("PairForge demo OK");
       }
   }
   ```

2. Select the matching language and press **Run** once. Wait for the actual
   terminal result. Capture only after **SUCCEEDED**, **Exit code: 0** and stdout
   `PairForge demo OK` are visible. Stderr should show **No errors yet.**
3. Include the source, selected language, execution state and output together.
   Adjust zoom or provide separate real editor/output crops if needed; preserve
   legibility. The displayed result must correspond to the displayed source.

**Hide first:** the execution UUID in **both** the **Recent executions** selector
and the **Execution … · Exit code …** line. Also remove account email, room UUID,
invitation values, browser URLs, AWS identifiers and unrelated/private output.
Keep the real status, exit code, duration and stdout/stderr unchanged.

## Before sharing and adding assets

- Save PNGs with the three neutral filenames above. Review the final pixels at
  full size. Use solid, opaque redactions rather than blur or removable overlays,
  and export a flattened image with no hidden layers or identifying metadata.
- No image may contain emails, account/room/execution UUIDs, invitation or session
  tokens, private code, AWS account/resource identifiers, or password-manager
  overlays. Do not share raw sensitive captures for later redaction.
- Inspect any replacement captures before placing them under `docs/assets/`.
  Add concise alt text and real relative image references near the README
  introduction. Do not add placeholders or nonexistent links.
- Repeat the current-tree privacy scan, image review and documentation/link
  checks whenever these assets are replaced.
- Stop local application terminals and use the documented local shutdown after
  capture. For AWS captures, use a separately approved operating window and
  verify the documented drain, cleanup, encrypted backup and stopped hosts at
  its end. Screenshot preparation does not automatically restart AWS.

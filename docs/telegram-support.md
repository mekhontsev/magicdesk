# Telegram Support And Test Builds

[MagicDesk Support Bot](https://t.me/MagicDeskSupportBot) lets you report a
problem, answer questions and try a proposed fix in one Telegram conversation.
You do not need a GitHub account, a development environment or a local AI setup.
The service uses GitHub Copilot to investigate reports and GitHub Actions to
build test APKs; it does not remotely control your phone or require MCP access.

## Send A Report

1. Join the [MagicDesk Telegram community](https://t.me/magicdesk_android).
   Open the bot in a **private chat**, not a group topic, and send `/start`.
2. Read its privacy notice. Use `/consent` to accept private storage, then
   `/new` to open a case. Keep one active case at a time.
3. Reproduce the problem, open **Diagnostics**, and press **Refresh**.
   Review and send the complete report as text or a `.txt` file. Include what
   you did, what you expected and what happened instead. See
   [Creating a report](compatibility.md#creating-a-report).
4. Send `/submit` when your description and report are ready. This queues them
   for processing; it does not mean work has already started. Use `/status`
   to check progress, including pauses while processing capacity is unavailable.

Reports may be in any language; the bot's prompts and follow-up questions are
currently in English. Do not send passwords, tokens, personal files or other
information you would not want included in a published code change.

## Questions, Fixes And Feedback

Copilot can ask for clarification before changing code. Answer in the same
chat and send `/submit` again when finished. Merely typing an answer does not
start another round of work. Send corrections as new messages: editing a
Telegram message does not update the saved report.

If a proposed change builds successfully, the bot sends the APK directly to
your chat. Its message includes the version, SHA-256 and links to the exact
source changes and GitHub build in
[magicdesk-test-builds](https://github.com/mekhontsev/magicdesk-test-builds).
You can inspect what changed without access to the private support lab.

After testing, describe what improved and what still fails, attach a fresh
report when relevant, and send `/submit`. The same case can continue through
further questions and builds. A clarification-only response does not produce
an APK. Confirmed fixes can be reviewed for inclusion in official MagicDesk;
the bot does not automatically merge them.

## Test APKs

**MagicDeskTest is experimental, not an official release or a verified fix.**
It has a separate package (`io.github.mekhontsev.magicdesk.test`) and test
signing key, so it does not replace the regular app as an Android update.
GitHub builds and signs it; installation is your choice, never automatic.

Separate packages do not isolate privileged changes to Android. Close regular
MagicDesk before trying a test build, and do not run both Desktop sessions
together. Coexistence has not been fully validated. Public source and a valid
signature let you inspect provenance; they do not guarantee safety or that a
patch fixes your problem. Grant powerful permissions only if you accept that risk.

The support pipeline is experimental, with limited processing capacity and
full real-user end-to-end validation still pending. There is no guaranteed
response time or fix. You can also report problems through
[GitHub issues](https://github.com/mekhontsev/magicdesk/issues).

## Privacy And Controls

Reports and replies are stored privately by the support service and can be
read by the maintainer. Submitted batches are sent to GitHub and processed by
Copilot in a private lab. They are not posted as public issue conversations.

Proposed source changes, build logs and test APKs are public. Private chat and
Git history are not copied into the public repository, but generated code can
include report details. Publication does not guarantee anonymization.

| Command | Purpose |
| --- | --- |
| `/status` | Show the current case and next step |
| `/submit` | Submit new report messages, answers or test results |
| `/apk` | Retry an unconfirmed APK delivery if the file did not arrive |
| `/cancel` | Cancel the case and stop pending local work and delivery |
| `/delete` | Erase your locally stored cases and withdraw storage consent |
| `/privacy` | Read the current storage and publication notice |
| `/help` | Show the bot's commands |

Cancellation cannot undo a cloud request already accepted. Deleting local case
data does not erase copies already sent to Telegram, GitHub or its AI provider,
or remove published source. Closed and cancelled cases expire from local
storage after 30 days.

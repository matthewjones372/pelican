# Existing core streaming request ownership

Separate follow-up; this admission slice preserves the measured native behaviour and does not fix ownership.
Checked baseline: main `fc2488fe5e09419fa78534cf6521ee7169f67da2`, `InMemory.kt:decodeBody` and
`spi/Requests.kt:readStrictBody`. Core opens `ClientRequest.Body.Streaming` for the strict reader; neither call
closes that input. An absent-admission synthetic oversized JSON request returned 413 with the opened stream still
unclosed. The admission test compares Continue with that baseline and closes its test-owned stream in finally.
Inspection/probe scope is the core in-memory strict-body path. A separate reviewed follow-up should settle opened
stream ownership for success, limits, defects and cancellation, including absent-hook compatibility, before a fix.

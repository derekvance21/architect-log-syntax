# architect-debug-lang

This is the architect-debug-lang syntax-highlighting and formatting language extension.

## Features

### Syntax Highlighting

This extension provides syntax highlighting through a TextMate grammar for Architect Debug Message Log text.

### Formatting

This extension provides formatting of Architect Debug Message Log text into properly indented and nested statements.

## Compile

```sh
vsce package
```

## Bugs

- [X] It will fail parsing if there's a closing curly brace inside SQL statement (happens for JSON operations)
- [X] End action
	- Example: `   22:                 End: N/A                                                          PASSED  Next Instruction: 0`
- [X] `SQL STATEMENT {BEGIN TRANSACTION}` failed parse b/c rn I require spaces between curly braces
- [X] the following line causs parse fail: `Value = [IV]`. Occurs when you set a field manually in debugger
- [X] "Line numbers" inside names pick up syntax highlighting. Change syntax grammar.
	- Example: `... Calculate: ScrOpt: F2:Item ...`
- [ ] Here's a sticky one that comes up in `demo/more-ops.archlog`. The last call to `_Directed Move - DMR` is a dynamic call, but it's at the end and never finishes in the log, so it doesn't have a `... Dynamic Call: Execute PO ...` line. So, b/c of the way Architect debug log works, if you go up from the bottom of the log you'll find an `ENTERING WANextGeneration._Directed Move - DMR` line, but you won't be able to tell that it got there from a `Dynamic Call: Execute PO` call. So the formatter just wraps up all the lines from the bottom up to the ENTERING line, and calls that a `... Call: _Directed Move - DMR ...` line with nested lines, rather than a dynamic call followed by a call.
	- The common issue here would be for calling a business process object from the main menu, so maybe you could hard code it where if the ENTERING line is preceded by the `Compare: MNU Dynamic Call = "Yes"?` line, then you should be OK to assume that it's a dynamic call. 

## TODO

- [X] Bundle
- [X] Dynamic Call statement will nest until Entering, but then it doesn't show what PO it Executed. It just says `Dynamic Call: Execute PO`, instead of `Dynamic Call: _Directed Move - DMR` - see `demo/all-ops.archlog` for example.
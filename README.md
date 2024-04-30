# architect-debug-lang

This is the architect-debug-lang syntax-highlighting language extension.

## Features

This extension provides syntax highlighting through a TextMate grammar for Architect Debug Message Log text.

## Compile

```sh
vsce package
```

## Bugs

- [X] It will fail parsing if there's a closing curly brace in SQL statement (like for JSON operations)
- [X] End action
>   22:                 End: N/A                                                          PASSED  Next Instruction: 0
- [X] `SQL STATEMENT {BEGIN TRANSACTION}` failed parse b/c rn I require spaces between curly braces
- [X] the following line causs parse fail: `Value = [IV]`. Occurs when you set a field manually in debugger
- [X] Numbers inside names pick up syntax highlighting. Change syntax grammar to require word breaks surrounding the integers.
	- Example: `... Calculate: ScrOpt: F2:Item ...`
- [X] Bundle
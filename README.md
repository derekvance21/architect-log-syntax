# architect-debug-lang

This is the architect-debug-lang syntax-highlighting language extension.

## Features

This extension provides syntax highlighting through a TextMate grammar for Architect Debug Message Log text.

## Compile

```sh
rm -r .shadow-cljs/builds # for some reason, new changes to resources/parser.bnf won't be read if this folder already exists
npx shadow-cljs compile formatter # compile the clojurescript formatter library into javascript
vsce package
```

## Bugs

- [X] It will fail parsing if there's a closing curly brace in SQL statement (like for JSON operations)
- [X] End action
>   22:                 End: N/A                                                          PASSED  Next Instruction: 0
- [X] `SQL STATEMENT {BEGIN TRANSACTION}` failed parse b/c rn I require spaces between curly braces

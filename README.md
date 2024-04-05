# architect-debug-lang

This is the architect-debug-lang syntax-highlighting language extension.

## Features

This extension provides syntax highlighting through a TextMate grammar for Architect Debug Message Log text.

## Compile

```sh
rm -r .shadow-cljs/builds # for some reason, new changes to resources/parser.bnf won't be ready if this folder already exists
npx shadow-cljs compile formatter # compile the clojurescript formatter library into javascript
npm run compile
vsce package
```

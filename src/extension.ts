// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as vscode from 'vscode';
const formatter = require('../out/formatter.js'); // compiled clojurescript

class ArchitectDocumentFormatter implements vscode.DocumentFormattingEditProvider {
	provideDocumentFormattingEdits(document: vscode.TextDocument, options: vscode.FormattingOptions, token: vscode.CancellationToken): vscode.ProviderResult<vscode.TextEdit[]> {
		const text: string = document.getText(); //.replace(/\r/g, '');
		try {
			const newText: string = formatter.transform(text);
			const start = new vscode.Position(0, 0);
			const end = document.positionAt(text.length); // can use Infinity, Infinity for end position `new vscode.Position(Infinity, Infinity)`
			const range = new vscode.Range(start, end);
			const replace = vscode.TextEdit.replace(range, newText);
			return [replace];
		} catch (e) {
			let errorMessage: string = 'Formatting failed';
			if (typeof e === 'string') {
				errorMessage = e;
			} else if (e instanceof Error) {
				errorMessage = e.message;
			}
			vscode.window.showErrorMessage(errorMessage);
			return [];
		}
	}
}

// this method is called when your extension is activated
// your extension is activated the very first time the command is executed
export function activate(context: vscode.ExtensionContext) {
	// Use the console to output diagnostic information (console.log) and errors (console.error)
	// This line of code will only be executed once when your extension is activated
	console.log('Congratulations, your extension "architect-debug-lang" is now active!');

	// The command has been defined in the package.json file
	// Now provide the implementation of the command with registerCommand
	// The commandId parameter must match the command field in package.json
	const disposable = vscode.languages.registerDocumentFormattingEditProvider(
		'architect',
		new ArchitectDocumentFormatter()
	);

	context.subscriptions.push(disposable);
}
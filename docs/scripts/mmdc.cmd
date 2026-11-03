@echo off
setlocal
rem Resolve Maven's Node.js and Mermaid installation from this script, not the working directory.
rem Keep the Windows config last so it takes precedence over Maven's forwarded config.
"%~dp0..\..\target\node\node.exe" "%~dp0..\..\target\node_modules\@mermaid-js\mermaid-cli\src\cli.js" %* --puppeteerConfigFile "%~dp0..\puppeteer-windows.json"
exit /b %errorlevel%

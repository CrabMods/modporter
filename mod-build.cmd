@echo off
python -B "%~dp0scripts\run_gradle.py" --project-root "%~dp0." %*
exit /b %errorlevel%

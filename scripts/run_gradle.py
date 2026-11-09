"""Run this repository's public Gradle wrapper."""

from pathlib import Path
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from modporter_build import main


if __name__ == "__main__":
    raise SystemExit(main(script_path=Path(__file__)))

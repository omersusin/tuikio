"""Game recipes. Each module registers its builders with tk.game(); importing this package
loads them all."""
import importlib
import os
import pkgutil

for _m in sorted(m.name for m in pkgutil.iter_modules([os.path.dirname(__file__)])):
    importlib.import_module(f"{__name__}.{_m}")

import m3
import subsubpkg as foo
from pkg import m5
from .subsubpkg import m4
from . import m2
from . import subsubpkg as bar

print(m2, m3, m4, m5, foo, bar)

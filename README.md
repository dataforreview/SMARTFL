# SmartFL

## Requiring
defects4j from https://github.com/rjust/defects4j

maven >= 3.6.0

python >= 3.6.9

pip install func_timeout numpy

## Run on defects4j
Modify defects4j with the following:

```
cd "Path2D4j"
git patch "Path2ThisRepo"/defects4j-mod/diff.patch
```

Localize a single bug:
```
python s.py fl {proj} {id}

e.g.
python s.py fl Lang 1
```

Test a project:
```
python s.py testproj {proj}

e.g.
python s.py testproj Lang
```



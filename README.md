# SmartFL

## Requiring
defects4j
pip install func_timeout

## Run on defects4j
Modify defects4j with the following:



```
cd "Path2D4j"
git patch "Path2ThisRepo"/defects4j-mod/diff.patch
```

Localize a single bug:
```
python s.py fl Lang 1
```

Test a project:
```
python s.py testproj Lang
```



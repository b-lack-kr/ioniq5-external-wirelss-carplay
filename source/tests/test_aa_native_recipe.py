"""Pinned48ee recipe, actual stock regions, assembled lane code and HMG-API application contract."""
from pathlib import Path
import hashlib,importlib.util,os,re,struct,subprocess,sys,tempfile
sys.dont_write_bytecode=True
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
workspace=next((p for p in source.parents if (p/'.tools').is_dir()),None)
stock_input=os.environ.get('NEEV_AA_STOCK')
if stock_input is None and workspace is not None:
    stock_input=workspace/'neev-carplay/research/live-ar-session-20261107/libautoreceiver_infobank.so'
if stock_input is None or not Path(stock_input).is_file():
    raise SystemExit('Set NEEV_AA_STOCK to the read-only stock libautoreceiver_infobank.so with MD5 48ee793af8b86052503b8c952b034731. No vehicle read is performed by this test.')
stock=Path(stock_input)
spec=importlib.util.spec_from_file_location('aa_recipe_builder',source/'mod/native/aa/build_recipe.py')
builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
clang,objcopy=builder.tools()
recipe=source/'mod/recipes/aa-tbt-48ee793a.recipe'
text=recipe.read_text(); segment=bytes.fromhex(re.search(r'^segment \S+ ([0-9a-f]+)$',text,re.M)[1])
assert hashlib.sha256(segment).hexdigest()=='77bc34c9fde926eaeb9849fab5b23d7cbf616a9be1dc6b576c7b7d22938980d4'
assert len(segment)==5574
data=stock.read_bytes()
assert hashlib.md5(data).hexdigest()=='48ee793af8b86052503b8c952b034731'
assert hashlib.sha256(data).hexdigest()=='eff8c509b0534950b5e7eeac9e4505e3ab92eb5e4cace27371c254281baa2840'
phoff=struct.unpack_from('<I',data,28)[0];phsize,phnum=struct.unpack_from('<HH',data,42)
loads=[struct.unpack_from('<IIIIIIII',data,phoff+k*phsize) for k in range(phnum)]
text_end=next(p[4] for p in loads if p[0]==1 and p[1]==0 and p[6]&1)
assert text_end==0x1a60ec
for off,length,md5 in re.findall(r'^verify (\S+) (\d+) ([0-9a-f]+) ',text,re.M):
    assert hashlib.md5(data[int(off,0):int(off,0)+int(length)]).hexdigest()==md5
assert 'id aa-tbt-48ee793a-neev-v0147\n' in text
assert builder.build(clang,objcopy)==recipe.read_bytes(), 'Full recipe does not reproduce from the pinned base and assembly sources'
with tempfile.TemporaryDirectory(prefix='neev-aa-recipe-') as tmp:
    tmp=Path(tmp)
    classes=tmp/'classes';classes.mkdir();jdk=java_bin(source)
    subprocess.run([jdk/'javac','--release','8','-d',classes,source/'upstream/HMG-API-1.0.1/core/runtime/java/hmgapi/engine/MemRecipe.java',source/'tests/aa-native/MemRecipeTest.java'],check=True)
    subprocess.run([jdk/'java','-cp',classes,'MemRecipeTest',stock,recipe,str(text_end)],check=True)
print('AA recipe PASS full byte-exact regeneration, stock hash/verify regions, lane assembly/tail')

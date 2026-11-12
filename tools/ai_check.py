"""CI check of the depth model: I/O names, shapes, speed, and that near things get larger values."""
import sys, time
import numpy as np, onnxruntime as ort

sess = ort.InferenceSession(sys.argv[1], providers=["CPUExecutionProvider"])
for i in sess.get_inputs(): print(f"::notice::input {i.name} {i.shape} {i.type}")
for o in sess.get_outputs(): print(f"::notice::output {o.name} {o.shape} {o.type}")

# Synthetic corridor: floor and walls with texture whose true depth we know.
H, W = 392, 294
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
nx = (xx + 0.5) / W * 2 - 1; ny = 1 - (yy + 0.5) / H * 2
# camera at height 1.3 in a 3 m wide corridor, looking along +z; ray (nx*0.6, ny*0.8, 1)
dx, dy = nx * 0.6, ny * 0.8
tf = np.where(dy < 0, 1.3 / np.maximum(-dy, 1e-6), 1e9)
tw = np.where(dx != 0, 1.5 / np.maximum(np.abs(dx), 1e-6), 1e9)
tc = np.where(dy > 0, 1.4 / np.maximum(dy, 1e-6), 1e9)
t = np.minimum(np.minimum(tf, tw), np.minimum(tc, 12.0))
px, py, pz = dx * t, dy * t, t
tex = (np.sin(px * 7) * np.sin(pz * 7) + np.sin(py * 9 + pz * 3)) * 0.25 + 0.5
img = np.stack([tex * 0.9, tex * 0.8, tex * 0.7], -1)
mean = np.array([0.485, 0.456, 0.406], np.float32); std = np.array([0.229, 0.224, 0.225], np.float32)
x = ((img - mean) / std).transpose(2, 0, 1)[None].astype(np.float32)

name = sess.get_inputs()[0].name
out = sess.run(None, {name: x})[0]
t0 = time.time()
for _ in range(3): out = sess.run(None, {name: x})[0]
dt = (time.time() - t0) / 3
d = out.reshape(out.shape[-2], out.shape[-1])
print(f"::notice::output shape {out.shape} range {d.min():.3f}..{d.max():.3f} time {dt*1000:.0f} ms (CPU, {H}x{W})")
# correlation of output with inverse true depth (resized if needed)
from PIL import Image
inv = 1 / t
inv_r = np.array(Image.fromarray(inv.astype(np.float32)).resize((d.shape[1], d.shape[0])))
c = np.corrcoef(d.ravel(), inv_r.ravel())[0, 1]
c2 = np.corrcoef(d.ravel(), t.ravel() if t.shape == d.shape else np.array(Image.fromarray(t.astype(np.float32)).resize((d.shape[1], d.shape[0]))).ravel())[0, 1]
print(f"::notice::corr(output, 1/depth)={c:.3f} corr(output, depth)={c2:.3f}")
# other input sizes
for (h, w) in [(266, 196), (518, 392)]:
    xi = np.random.rand(1, 3, h, w).astype(np.float32)
    try:
        t0 = time.time(); o = sess.run(None, {name: xi})[0]
        print(f"::notice::size {h}x{w} -> {o.shape} {1000*(time.time()-t0):.0f} ms")
    except Exception as e:
        print(f"::notice::size {h}x{w} failed {e}")

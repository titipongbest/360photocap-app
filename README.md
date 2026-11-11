# 360 PhotoCap

แอพ Android ถ่ายภาพ 360° แบบ photo sphere: แอพวางจุดเป้าบนทรงกลมรอบตัวผู้ใช้ ผู้ใช้หมุนกล้องเล็งตามจุด แอพถ่ายอัตโนมัติเมื่อเล็งตรงและถือนิ่ง แล้วต่อภาพเป็น panorama แบบ equirectangular (2:1) พร้อม metadata GPano ให้ Google Photos / Facebook เปิดเป็นภาพ 360 ได้

## ดาวน์โหลด APK

ทุกครั้งที่ push เข้า `main` GitHub Actions จะ build APK ให้
แท็บ **Actions** → run ล่าสุด → **Artifacts** → `360PhotoCap-apk`

Build เอง: `./gradlew assembleRelease` (ต้องมี JDK 17 + Android SDK)

## การทำงาน

1. **อ่านทิศกล้องด้วย gyroscope** (`OrientationTracker.kt`)
   - ใช้ `TYPE_GAME_ROTATION_VECTOR` ซึ่ง Android รวม gyroscope + accelerometer ให้ โดย**ไม่ใช้เข็มทิศ** จึงไม่เพี้ยนเพราะโต๊ะเหล็ก/ตู้เหล็กในอาคาร
   - ถ้าเครื่องไม่มี sensor นี้ จะอินทิเกรต `TYPE_GYROSCOPE` เองด้วย quaternion และใช้ accelerometer แก้ drift ของ pitch/roll (Mahony filter)
   - ผลลัพธ์คือเมทริกซ์การหมุน **R** (device → world)
2. **วางเป้า** (`SphereMath.buildTargets`) ตาม FOV ของกล้องที่อ่านจาก Camera2
3. **วาดเป้าทับภาพกล้อง** (`TargetOverlayView.kt`) และแสดงภาพที่ถ่ายแล้วเป็นกรอบสีเขียว
4. **ถ่ายอัตโนมัติ** (`CaptureActivity.kt`) เมื่อเล็งห่างเป้า < 3°, เอียงข้าง < 12°, หมุนช้ากว่า 0.1 rad/s ต่อเนื่อง 450 ms
   ถ้าขยับเกิน 1.5° ระหว่างชัตเตอร์ทำงาน จะทิ้งภาพแล้วให้ถ่ายใหม่ และล็อก AE/AWB ตั้งแต่ภาพแรกให้สีทุกภาพเท่ากัน
5. **ต่อภาพ** (`StitchCore.kt`) แบบ inverse mapping ใช้ทุก core ของ CPU
6. **บันทึก** (`PanoSaver.kt`) ลง `Pictures/360PhotoCap` พร้อม XMP GPano และเปิดดูในแอพด้วย OpenGL (`PanoViewerView.kt`)

## การคำนวณ

**แกนพิกัด** (แบบเดียวกับ Android SensorManager)
- World: X = ตะวันออก, Y = เหนือ (กับ game rotation vector คือทิศอ้างอิงที่เครื่องเลือกเอง), Z = ขึ้นบน
- Device (แนวตั้ง): X = ขวา, Y = หัวเครื่อง, Z = พุ่งออกจากจอ กล้องหลังมองไปทาง −Z
- `w = R · d` แปลงจาก device เป็น world

**FOV กล้อง**

```
tan(FOV/2) = (ขนาด sensor × activeArray/pixelArray) / 2 / ระยะโฟกัส
```
แล้วครอปตามอัตราส่วนภาพจริง (4:3) ด้วย `cropTan`

**ทิศของเป้า** (yaw θ จากทิศแรกที่ถ่าย, pitch φ)

```
v = (cos φ · sin(θ+θ₀), cos φ · cos(θ+θ₀), sin φ)
```

**จำนวนเป้า** (overlap 35%)

```
ระยะห่างแถว  = vFOV · (1 − overlap)
ภาพต่อแถว    N = ceil(360° · cos φ / (hFOV · (1 − overlap)))
```
กล้องทั่วไป (แนวตั้ง ~50° × 65°): แถวกลาง 12 + แถว ±41° แถวละ 9 + เพดาน 1 + พื้น 1 = **32 ภาพ**

**เช็กว่าเล็งตรง**

```
f = R · (0, 0, −1)        มุมคลาด = arccos(f · v)
```

**ฉายเป้าลงจอ**

```
p = Rᵀ · v,  depth = −p.z
x = cx + (p.x/depth)/tan(hFOV/2) · W/2
y = cy − (p.y/depth)/tan(vFOV/2) · H/2
```
ขอบภาพเป็นเส้นวงกลมใหญ่บนทรงกลม จึงฉายลงจอเป็นเส้นตรง วาดกรอบภาพที่ถ่ายแล้วได้จาก 4 มุมพอ

**ต่อภาพ (equirectangular W×H)** สำหรับทุกพิกเซล (u, v):

```
lon = (u+½)/W · 2π − π         lat = π/2 − (v+½)/H · π
d   = (cos lat · sin(lon+θ₀), cos lat · cos(lon+θ₀), sin lat)
ทุกภาพ i:   p = Rᵢᵀ·d,   nx = (p.x/−p.z)/tanH,   ny = (p.y/−p.z)/tanV
           อยู่ในภาพถ้า |nx|,|ny| < 1  →  ดึงสีแบบ bilinear ที่ ((nx+1)/2·w, (1−ny)/2·h)
           น้ำหนัก = ((1−nx²)(1−ny²))³     (กลางภาพสำคัญสุด ขอบจางลง)
สี = Σ น้ำหนัก·สี / Σ น้ำหนัก
```
ความกว้างภาพผลลัพธ์ = 2π · (w/2)/tanH ตามความละเอียดที่แนวนอน (จำกัดตามหน่วยความจำ สูงสุด 8192)

**Parallax** มุมเหลื่อม ≈ r · Δθ / d (r = ระยะกล้องจากแกนหมุน, d = ระยะวัตถุ)
จึงต้องถือเครื่องชิดอก หมุนอยู่กับที่ และยืนห่างวัตถุ

## ข้อจำกัดของเวอร์ชันนี้

- ต่อภาพด้วยทิศจาก gyroscope อย่างเดียว ความคลาด 1–2° อาจเห็นรอยต่อซ้อนบนเส้นตรงที่อยู่ใกล้
  ขั้นต่อไปคือปรับ R ให้ละเอียดด้วย feature matching (ORB) + bundle adjustment
- ยังไม่มีการชดเชยความสว่างระหว่างภาพ (ใช้การล็อก AE/AWB แทน)
- รองรับมือถือที่แนวปกติเป็นแนวตั้ง (ไม่ใช่แท็บเล็ต)

## โครงสร้างไฟล์

```
app/src/main/java/com/titipong/photocap360/
  MainActivity.kt        หน้าแนะนำวิธีถ่าย
  CaptureActivity.kt     กล้อง + state machine ถ่ายอัตโนมัติ
  OrientationTracker.kt  gyroscope → R
  TargetOverlayView.kt   วาดเป้า, กรอบภาพที่ถ่ายแล้ว, ลูกศร, แผนที่จุด
  SphereMath.kt          คณิตศาสตร์ทรงกลม + วางเป้า
  CaptureSession.kt      เก็บภาพ + R เป็น session.json
  StitchCore.kt          ต่อภาพ equirectangular (pure Kotlin, มี unit test)
  Stitcher.kt            โหลดภาพ, แบ่งงานหลายเธรด
  PanoSaver.kt           JPEG + XMP GPano, บันทึกแกลเลอรี
  PanoViewerView.kt      ตัวดูภาพ 360 (OpenGL ES 2)
  StitchActivity.kt      หน้าต่อภาพ + ดูผล + แชร์
```

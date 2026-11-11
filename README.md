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
5. **จัดภาพให้ตรงกัน** (`Aligner.kt`) หาจุดที่ตรงกันระหว่างภาพที่ซ้อนกัน แล้วปรับทิศของแต่ละภาพและค่า FOV ให้รอยต่อสนิท (รายละเอียดด้านล่าง)
6. **ต่อภาพ** (`StitchCore.kt`) แบบ inverse mapping ใช้ทุก core ของ CPU
7. **บันทึก** (`PanoSaver.kt`) ลง `Pictures/360PhotoCap` พร้อม XMP GPano และเปิดดูในแอพด้วย OpenGL (`PanoViewerView.kt`)

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

**จัดภาพให้ตรงกัน (แก้ภาพซ้อน)** `Aligner.kt`

1. หามุม (Shi-Tomasi corner) แบบกระจายเป็นตาราง 12×16 ช่องในแต่ละภาพ
2. ทุกคู่ภาพที่ซ้อนกัน: ใช้ R จาก gyroscope ทายว่ามุมจากภาพ i จะไปอยู่ตรงไหนในภาพ j
   แล้วบิดแพตช์รอบจุดนั้นให้เป็นมุมมองของภาพ j ด้วย homography `H = K_i · R_iᵀ · R_j · K_j⁻¹`
   จากนั้นค้นหาด้วย normalized cross-correlation จากหยาบไปละเอียด 3 ชั้น
   (ชั้น ¼ ค้น ±16 px ≈ ±7°, แล้ว ±2 px) และหาตำแหน่งละเอียดกว่าพิกเซลด้วย parabola
   ตัดจุดที่กำกวม (ลายซ้ำ เช่น กระเบื้อง) ด้วยการเทียบยอดที่ 1 กับยอดที่ 2
3. Bundle adjustment (Levenberg-Marquardt) ปรับการหมุนเล็กๆ ωᵢ ของทุกภาพ + สเกล FOV ร่วม σ:
   ```
   r = normalize(Rᵢ·vᵢ) − normalize(Rⱼ·vⱼ),     v = (nx·tanH·eᵠ, ny·tanV·eᵠ, −1)
   Rᵢ ← exp([ωᵢ]×)·Rᵢ,   ∂r/∂ωᵢ = −[uᵢ]×,   ∂r/∂ωⱼ = +[uⱼ]×
   ต้นทุน = Σ Huber(|r|, 0.23°) + w·Σ|log(Rᵢ·Rᵢ,gyroᵀ)|² + w·σ²
   ```
   prior เบาๆ ดึงไว้ใกล้ค่า gyroscope เพื่อให้เส้นขอบฟ้าตรงและล็อกการหมุนรวม
   ตัด outlier (> max(0.57°, 4×median)) แล้วปรับซ้ำ
4. ถ้าจับคู่ได้น้อยหรือผลแปลก (rms > 1°, FOV เปลี่ยน > 15%, ภาพไหนขยับ > 12°) จะกลับไปใช้ค่า gyroscope

ผลทดสอบกับฉากจำลอง (32 ภาพ, gyro คลาดสุ่ม ≤ 2.5°, FOV ผิด 3%):
ความคลาดการหมุนระหว่างภาพ 4.5° → **0.03°**, FOV กลับมาถูก, ความต่างสีตรงรอยต่อ 21 → **1.8** (จาก 255)

**ต่อภาพ (equirectangular W×H)** สำหรับทุกพิกเซล (u, v):

```
lon = (u+½)/W · 2π − π         lat = π/2 − (v+½)/H · π
d   = (cos lat · sin(lon+θ₀), cos lat · cos(lon+θ₀), sin lat)
ทุกภาพ i:   p = Rᵢᵀ·d,   nx = (p.x/−p.z)/tanH,   ny = (p.y/−p.z)/tanV
           อยู่ในภาพถ้า |nx|,|ny| < 1  →  ดึงสีแบบ bilinear ที่ ((nx+1)/2·w − ½, (1−ny)/2·h − ½)
           margin = min((1−|nx|)·tanH, (1−|ny|)·tanV)     (ระยะถึงขอบภาพ ≈ เป็นมุม)
ภาพที่ margin มากสุดเป็นเจ้าของพิกเซล ภาพอื่นผสมเฉพาะแถบรอยต่อกว้าง ~2°:
           t = (marginᵢ − best)/0.035 + 1,   น้ำหนัก = smoothstep(t)
```
แบบนี้แต่ละพิกเซลมาจากภาพเดียวเป็นหลัก จึงไม่เกิดเงาซ้อน
ความกว้างภาพผลลัพธ์ = 2π · (w/2)/tanH ตามความละเอียดที่แนวนอน (จำกัดตามหน่วยความจำ สูงสุด 8192)

**Parallax** มุมเหลื่อม ≈ r · Δθ / d (r = ระยะกล้องจากแกนหมุน, d = ระยะวัตถุ)
จึงต้องถือเครื่องชิดอก หมุนอยู่กับที่ และยืนห่างวัตถุ

## ข้อจำกัดของเวอร์ชันนี้

- การจัดภาพแก้การหมุนได้ แต่แก้ parallax ไม่ได้ วัตถุที่อยู่ใกล้กล้องไม่ถึง 1 เมตรยังเหลื่อมตรงรอยต่อ
  วิธีถ่าย: ให้มือถืออยู่จุดเดิมในอากาศแล้วเดินวนรอบมัน และยืนห่างโต๊ะ/ผนัง
  ขั้นต่อไป: ให้รอยต่อวิ่งหลบวัตถุ (seam finding)
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
  Aligner.kt             จับคู่จุด + bundle adjustment ปรับทิศและ FOV (pure Kotlin, มี unit test)
  StitchCore.kt          ต่อภาพ equirectangular + เบลนด์รอยต่อ (pure Kotlin, มี unit test)
  Stitcher.kt            โหลดภาพ, แบ่งงานหลายเธรด
  PanoSaver.kt           JPEG + XMP GPano, บันทึกแกลเลอรี
  PanoViewerView.kt      ตัวดูภาพ 360 (OpenGL ES 2)
  StitchActivity.kt      หน้าต่อภาพ + ดูผล + แชร์
```

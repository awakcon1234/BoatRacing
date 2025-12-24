# BoatRacing

BoatRacing là plugin đua thuyền băng phong cách F1 cho Bukkit/Spigot (tương thích Paper/Purpur). Plugin cung cấp công cụ chọn vùng tích hợp, GUI cấu hình đường đua, HUD (Scoreboard + ActionBar), giai đoạn chờ trước khi đua và đếm ngược bằng “đèn xuất phát”.

Trạng thái: phát hành công khai • Hỗ trợ 1.19 → 1.21.8 • Yêu cầu Java 17+

—

Mục lục
- Giới thiệu nhanh
- Cài đặt
- Tính năng chính
- Hướng dẫn thiết lập đường đua (chi tiết)
- Tổ chức cuộc đua (chờ và bắt đầu)
- Lệnh & quyền
- Cấu hình (config.yml)
- Lưu trữ dữ liệu
- Giấy phép

## Giới thiệu nhanh
- Mỗi đường đua nằm trên 1 thế giới (world) duy nhất. Tên world được lưu 1 lần trong file track, mọi thành phần khác (start/finish/checkpoint/đèn/điểm spawn chờ/centerline) đều kế thừa world này.
- Khu vực bao (bounds) và vùng đích/checkpoint được làm tròn về số nguyên (… .0) để khớp với ô khối.
- Vị trí xuất phát (start) được “snap”: X/Z về .0 hoặc .5 gần nhất, pitch=0, yaw làm tròn bội số 45°.
- Giai đoạn chờ 30s: người chơi tham gia sẽ được dịch chuyển tới điểm spawn chờ trong khu vực đường đua để đi lại tự do trước khi bắt đầu.
- Bắt đầu đua: đếm ngược 3-2-1 trên Title, Subtitle hiển thị 3 chấm tròn (đỏ→vàng→xám/ xanh) mô phỏng đèn xuất phát; tới GO! sẽ phát âm thanh và cuộc đua bắt đầu.

## Tính năng chính
- Công cụ chọn vùng tích hợp (wand), GUI quản lý đường đua.
- Đường đua nhiều thành phần: Start, Finish, Checkpoint (tuỳ chọn), Đèn xuất phát (Redstone Lamp), Centerline (đường giữa – tuỳ chọn), Khu vực bao (bounds), Điểm spawn chờ.
- Chờ trước khi đua 30s (mặc định), tự động dịch chuyển người tham gia tới điểm chờ và tự động xếp vào vị trí start khi hết thời gian.
- Đếm ngược bắt đầu phong cách đèn xuất phát (3s): Title hiện 3-2-1, Subtitle hiện chấm tròn đổi màu, GO! bắt đầu đua.
- HUD: Bảng bên (Scoreboard) và ActionBar có thể tuỳ biến; hiển thị tiến độ, vòng, vị trí, tốc độ…

## Hướng dẫn thiết lập đường đua (chi tiết)
Bạn có thể dùng GUI: `/boatracing admin` → “Đường đua” hoặc dùng lệnh trực tiếp.

1) Tạo/chọn đường đua
- GUI: “Chọn đường” để chọn, “Tạo mới” để đặt tên và tạo file `tracks/<name>.yml`.
  - Plugin lưu “world” ở cấp cao nhất của file. Toàn bộ thành phần track sẽ sử dụng world này.

2) Đặt khu vực bao (Bounds) – tuỳ chọn nhưng nên có
- Đặt hai góc bằng wand hoặc lệnh nhanh:
  - `/boatracing setup pos1` (Góc A = vị trí hiện tại)
  - `/boatracing setup pos2` (Góc B = vị trí hiện tại)
- Lưu làm bounds:
  - `/boatracing setup setbounds`
- GUI tương đương: “Đặt Vùng bao”.
- Lưu ý: Bounds tự động làm tròn về số nguyên (.0).

3) Đặt điểm Spawn chờ
- Đứng tại vị trí muốn đặt và chạy:
  - `/boatracing setup setwaitspawn`
- GUI tương đương: “Đặt Spawn chờ”.
- Vị trí spawn chờ cũng được “snap” như start (X/Z .0/.5, pitch=0, yaw 45°).

4) Thêm các vị trí Start
- Đứng tại vị trí mong muốn và chạy:
  - `/boatracing setup addstart`
- Lặp lại để thêm đủ số chỗ xuất phát. Thứ tự thêm chính là thứ tự lưới xuất phát.
- Lưu ý: Mỗi start được chuẩn hoá: X/Z → .0/.5, pitch=0, yaw → bội 45°.

5) Đặt vùng Đích (Finish)
- Dùng wand chọn 2 góc hoặc pos1/pos2 → `/boatracing setup setfinish`
- GUI tương đương: “Đặt Đích”.

6) Thêm Checkpoint (tuỳ chọn)
- Dùng wand chọn 2 góc theo đúng thứ tự đường chạy → `/boatracing setup addcheckpoint`
- Xoá toàn bộ checkpoint: `/boatracing setup clearcheckpoints`

7) Thêm Đèn xuất phát (tuỳ chọn)
- Nhìn vào Redstone Lamp trong tầm 6 block → `/boatracing setup addlight` (tối đa 5)
- Xoá tất cả đèn: `/boatracing setup clearlights`

8) Xây dựng đường giữa (Centerline – tuỳ chọn)
- GUI: “Xây dựng đường giữa” để tạo đường đi tham chiếu (A*)

9) Lưu track
- GUI: “Lưu” hoặc “Lưu thành…”.

## Tổ chức cuộc đua (chờ và bắt đầu)
1) Mở đăng ký (chờ)
- `/boatracing race open <tên_đường>`
- Thời gian chờ mặc định 30s (config: `racing.registration-seconds`).
- Người chơi tham gia bằng `/boatracing race join <tên_đường>` sẽ được dịch chuyển tới “Spawn chờ” (nếu đặt) và có thể đi lại trong khu vực đường đua.

2) Hết chờ → xếp Start → đếm ngược
- Khi hết thời gian:
  - Nếu không có ai đăng ký: tự huỷ đăng ký.
  - Nếu có người: plugin xếp người chơi vào các vị trí Start (theo thứ tự đã thêm), gắn thuyền và bắt đầu đếm ngược 3-2-1.
- Đếm ngược đèn xuất phát:
  - Title: “3”, “2”, “1”; Subtitle: ba chấm tròn đổi màu (đỏ/vàng/xám → xám/xám/xanh); âm thanh click theo nhịp.
  - GO!: Title “GO!”, Subtitle chấm xanh; phát âm thanh bắt đầu.

3) Dừng/cưỡng chế
- Dừng: `/boatracing race stop <tên_đường>`
- Cưỡng chế bắt đầu ngay (bỏ chờ): `/boatracing race force <tên_đường>`
- Bắt đầu ngay (nếu đã đăng ký): `/boatracing race start <tên_đường>`

## Lệnh & quyền
- `/boatracing admin` — mở bảng Quản trị (cần `boatracing.admin`), trong đó có “Đường đua” và “Cuộc đua”.
- Lệnh thiết lập:
  - `/boatracing setup help`
  - `/boatracing setup wand` — phát gậy chọn vùng
  - `/boatracing setup pos1`, `/boatracing setup pos2` — đặt góc A/B theo vị trí hiện tại
  - `/boatracing setup setbounds` — lưu vùng bao từ selection hiện tại
  - `/boatracing setup setwaitspawn` — đặt điểm spawn chờ
  - `/boatracing setup addstart`, `/boatracing setup clearstarts`
  - `/boatracing setup setfinish`
  - `/boatracing setup addcheckpoint`, `/boatracing setup clearcheckpoints`
  - `/boatracing setup addlight`, `/boatracing setup clearlights`
  - `/boatracing setup show`, `/boatracing setup selinfo`
- Lệnh đua:
  - `/boatracing race open|start|force|stop <track>` (quản trị)
  - `/boatracing race join|leave|status <track>` (người chơi)

Quyền cơ bản:
- `boatracing.setup` — thiết lập đường đua & mở GUI “Đường đua”
- `boatracing.race.admin` — điều khiển cuộc đua (open/start/force/stop)
- `boatracing.admin` — mở GUI quản trị
- `boatracing.version`, `boatracing.reload` — xem phiên bản / tải lại cấu hình

## Cấu hình (config.yml)
Các khoá tiêu biểu:
- `racing.laps`: số vòng (mặc định 3)
- `racing.registration-seconds`: thời gian chờ trước khi đua (mặc định 30)
- `racing.false-start-penalty-seconds`, `racing.enable-false-start-penalty`: phạt xuất phát sớm
- `racing.lights-out-delay-seconds` và `racing.lights-out-jitter-seconds`: tinh chỉnh nhịp đèn ra lệnh “GO!”
- Tùy biến HUD: xem phần `scoreboard.ui` trong config (ScoreboardService hỗ trợ PlaceholderAPI + MiniMessage)

## Lưu trữ dữ liệu
- Mỗi đường đua là 1 file: `plugins/BoatRacing/tracks/<tên>.yml`
  - `world` (chuỗi) — world của toàn bộ đường
  - `starts` (danh sách vị trí), `finish` (vùng), `checkpoints` (danh sách vùng), `lights` (toạ độ block), `centerline` (danh sách vị trí), `waitingSpawn` (vị trí), `bounds` (vùng)

## Giấy phép
MIT License — xem `LICENSE`.

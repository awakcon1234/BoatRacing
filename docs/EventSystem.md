# Event system (BoatRacing) — design draft

## Mục tiêu
Thêm tính năng **Sự kiện (Event)** gồm nhiều chặng (nhiều đường đua), cho phép tay đua đăng ký trước, thi đấu lần lượt theo pool đường đua, và xếp hạng bằng điểm theo luật Mario Kart. Kết thúc sự kiện sẽ hiển thị top 3 ở bục trong sảnh bằng NPC giả (skin của người chơi) với 2 dòng tên (dòng 1: racer display, dòng 2: tổng điểm).

## Khái niệm dữ liệu
**Event** có:
- `id` (chuỗi khóa, duy nhất)
- `title` (tiêu đề)
- `description` (mô tả)
- `startTimeMillis` (thời gian bắt đầu)
- `trackPool` (danh sách tên track)
- `state` (DRAFT, REGISTRATION, RUNNING, COMPLETED, CANCELLED)
- `currentTrackIndex` (đang chạy track thứ mấy)
- `participants` (map UUID -> trạng thái + điểm)

**Participant**:
- `uuid`, `nameSnapshot`
- `pointsTotal`
- `status` (REGISTERED, ACTIVE, LEFT)
- `lastSeenMillis`

## Luồng chạy sự kiện (tóm tắt)
1. Admin tạo event (DRAFT) và set tiêu đề/mô tả/giờ/track pool.
2. Mở đăng ký (REGISTRATION).
3. Tay đua đăng ký trước (command hoặc GUI).
4. Đến `startTimeMillis` hoặc admin start: event sang RUNNING.
5. Với mỗi track trong pool:
   - Mở race theo track đó.
   - Chỉ những người thuộc event (và chưa LEFT) được tham gia.
   - Khi chặng kết thúc: tính điểm theo thứ hạng, cộng vào tổng.
   - Teleport toàn bộ tay đua về lobby + countdown chuẩn bị chặng tiếp theo.
6. Hết track pool: event COMPLETED.
7. Spawn podium NPC top3 ở lobby.

## Điểm (Mario Kart)
Mặc định dùng bảng điểm MK-style cho tối đa 12 người:
`[15, 12, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1]`
- Nếu số người < 12: lấy prefix tương ứng.
- Nếu số người > 12: từ hạng 13+ nhận 0.

DNF / rời giữa chặng:
- Mặc định: coi như hạng cuối (được điểm thấp nhất của bảng, hoặc 0 nếu vượt quá 12).

## Edge cases cần xử lý
- Rời event giữa chừng: giữ điểm đã có, status=LEFT, không auto-join các chặng tiếp.
- Rời giữa race: tính như DNF cho chặng đó.
- Join event sau khi đã RUNNING:
  - Mặc định: cho đăng ký vào “late join”, nhưng các chặng đã qua nhận 0 điểm.
  - Option: cấm late join (cấu hình).
- Người chơi offline khi chuyển chặng: điểm vẫn ghi nhận; khi online lại có thể tiếp tục nếu chưa LEFT.
- Không đủ start slots trên track: chỉ seat được subset; phần còn lại coi như DNF hoặc chờ (cấu hình).

## Tích hợp kiến trúc hiện tại
- Race runtime là per-track qua `RaceService`/`RaceManager`.
- Event phải là 1 tầng orchestration ở trên: điều phối việc start track tiếp theo, đợi kết thúc, tính điểm.
- Cần hook “race finished” từ `RaceManager` để event biết khi nào chặng kết thúc.

## Câu hỏi cần chốt (để implement đầy đủ)
1. Sự kiện có cho phép **nhiều event chạy song song** hay chỉ 1 event active tại 1 thời điểm?
2. “Lobby” chính xác là **vị trí nào**? (world spawn hay config `lobby.location`?)
3. DNF muốn tính điểm là **0** hay **điểm hạng cuối**?
4. Khi thiếu start slots: ưu tiên theo đăng ký trước hay chọn ngẫu nhiên?

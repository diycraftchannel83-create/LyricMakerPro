package com.winsmediatama.lyricmakerpro
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppLanguage {
 val supported = listOf("English","Indonesia","Malaysia","Thailand","China","Korea","Jepang","Arab","Rusia","India")
 var current by mutableStateOf("English")
 private val data = mapOf(
 "English" to listOf("Insert Audio","Insert Picture","Insert Video","Insert Lyrics (.TXT / .LRC)","Open Project","Save Project","Save Lyrics (.LRC)","Privacy Policy","Language","About","Exit","Export","Close","Timeline","Fit","Sync","Done","Edit Lyric","Correct lyric text","Installed version","Version code"),
 "Indonesia" to listOf("Masukkan Audio","Masukkan Gambar","Masukkan Video","Masukkan Lirik (.TXT / .LRC)","Buka Proyek","Simpan Proyek","Simpan Lirik (.LRC)","Kebijakan Privasi","Bahasa","Tentang","Keluar","Ekspor","Tutup","Linimasa","Sesuaikan","Sinkron","Selesai","Edit Lirik","Perbaiki teks lirik","Versi terpasang","Kode versi"),
 "Malaysia" to listOf("Masukkan Audio","Masukkan Gambar","Masukkan Video","Masukkan Lirik (.TXT / .LRC)","Buka Projek","Simpan Projek","Simpan Lirik (.LRC)","Dasar Privasi","Bahasa","Perihal","Keluar","Eksport","Tutup","Garis Masa","Muat","Segerak","Selesai","Edit Lirik","Betulkan teks lirik","Versi dipasang","Kod versi"),
 "Thailand" to listOf("นำเข้าเสียง","นำเข้ารูปภาพ","นำเข้าวิดีโอ","นำเข้าเนื้อเพลง","เปิดโปรเจกต์","บันทึกโปรเจกต์","บันทึกเนื้อเพลง","นโยบายความเป็นส่วนตัว","ภาษา","เกี่ยวกับ","ออก","ส่งออก","ปิด","ไทม์ไลน์","พอดี","ซิงค์","เสร็จสิ้น","แก้ไขเนื้อเพลง","แก้ไขข้อความเนื้อเพลง","เวอร์ชันที่ติดตั้ง","รหัสเวอร์ชัน"),
 "China" to listOf("导入音频","导入图片","导入视频","导入歌词","打开项目","保存项目","保存歌词","隐私政策","语言","关于","退出","导出","关闭","时间轴","适应","同步","完成","编辑歌词","修改歌词文本","已安装版本","版本代码"),
 "Korea" to listOf("오디오 추가","사진 추가","동영상 추가","가사 추가","프로젝트 열기","프로젝트 저장","가사 저장","개인정보 처리방침","언어","정보","종료","내보내기","닫기","타임라인","맞춤","동기화","완료","가사 편집","가사 텍스트 수정","설치된 버전","버전 코드"),
 "Jepang" to listOf("音声を追加","画像を追加","動画を追加","歌詞を追加","プロジェクトを開く","プロジェクトを保存","歌詞を保存","プライバシーポリシー","言語","アプリ情報","終了","書き出し","閉じる","タイムライン","全体表示","同期","完了","歌詞を編集","歌詞テキストを修正","インストール済みバージョン","バージョンコード"),
 "Arab" to listOf("إضافة صوت","إضافة صورة","إضافة فيديو","إضافة كلمات الأغنية","فتح المشروع","حفظ المشروع","حفظ الكلمات","سياسة الخصوصية","اللغة","حول التطبيق","خروج","تصدير","إغلاق","الجدول الزمني","ملاءمة","مزامنة","تم","تعديل الكلمات","تصحيح نص الكلمات","الإصدار المثبت","رمز الإصدار"),
 "Rusia" to listOf("Добавить аудио","Добавить изображение","Добавить видео","Добавить текст песни","Открыть проект","Сохранить проект","Сохранить текст","Политика конфиденциальности","Язык","О приложении","Выход","Экспорт","Закрыть","Шкала времени","По размеру","Синхр.","Готово","Изменить текст","Исправить текст песни","Установленная версия","Код версии"),
 "India" to listOf("ऑडियो जोड़ें","चित्र जोड़ें","वीडियो जोड़ें","गीत जोड़ें","प्रोजेक्ट खोलें","प्रोजेक्ट सहेजें","गीत सहेजें","गोपनीयता नीति","भाषा","ऐप के बारे में","बाहर जाएँ","एक्सपोर्ट","बंद करें","टाइमलाइन","फिट","सिंक","हो गया","गीत संपादित करें","गीत का पाठ सुधारें","इंस्टॉल किया गया संस्करण","संस्करण कोड")
 )
 private val keys = listOf("Insert Audio","Insert Picture","Insert Video","Insert Lyrics (.TXT / .LRC)","Open Project","Save Project","Save Lyrics (.LRC)","Privacy Policy","Language","About","Exit","Export","Close","Timeline","Fit","Sync","Done","Edit Lyric","Correct lyric text","Installed version","Version code")
 fun text(source:String):String { val i=keys.indexOf(source); return if(i<0) source else data[current]?.getOrNull(i) ?: source }
}

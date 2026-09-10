import type { MessageKey } from './messages.en.ts';

/**
 * Türkçe — the same set of sentences, rewritten rather than translated word by word.
 *
 * <p>Typed as `Record<MessageKey, string>`, so an English key added without a Turkish one does not
 * compile. That is the whole reason this is a TypeScript file and not JSON.
 *
 * <h2>Three places the English shape had to be abandoned, not translated</h2>
 *
 * <ul>
 *   <li><b>Word order.</b> Turkish is verb-final: "Loading your training…" is
 *       "Eğitimleriniz yükleniyor…" — the verb moved to the end and the noun took a possessive
 *       suffix. No arrangement of an English prefix and an English noun produces that sentence,
 *       which is why the catalogue holds whole sentences.
 *   <li><b>Suffixes belong to the word before them.</b> Anything interpolated into a sentence is a
 *       course title or a number the customer owns, and the correct Turkish suffix depends on that
 *       word's last vowel. So placeholders sit where a bare noun can stand — never immediately
 *       before a case ending we would have to guess.
 *   <li><b>The percent sign goes first.</b> "%62", not "62%". That is `Intl.NumberFormat`'s job in
 *       `format.ts` rather than a string here, which is why there is no `{percent}%` entry.
 * </ul>
 *
 * <p><b>Not yet reviewed by a native speaker.</b> The wording below is idiomatic but the domain
 * terms — "overdue", "awaiting grading", "gated" — are the kind a compliance team has house words
 * for. This file is one flat object precisely so that review is a cheap pass.
 */
export const tr: Record<MessageKey, string> = {
  // --------------------------------------------------------------------------------- the shell
  'shell.skip': 'İçeriğe geç',
  'shell.nav': 'Ana menü',
  'shell.tenant.console': 'Konsol',
  'shell.tenant.learner': 'Eğitimleriniz',
  'shell.nav.authoring': 'İçerik',
  'shell.nav.assign': 'Atama',
  'shell.nav.grading': 'Değerlendirme',
  'shell.nav.people': 'Kullanıcılar',
  'shell.nav.roles': 'Roller',
  'shell.nav.compliance': 'Raporlar',
  'shell.to-console': 'Konsol',
  'shell.to-learner': 'Eğitimleriniz',
  'shell.sign-out': 'Çıkış yap',
  'shell.sign-in': 'Giriş yap',
  'shell.tab.training': 'Eğitim',
  'shell.tab.progress': 'İlerleme',
  'shell.tab.discover': 'Keşfet',
  'shell.product': 'XenOpsBase Learn',
  'shell.menu': 'Menü',
  'shell.notifications': 'Bildirimler',
  'shell.notifications.none': 'Henüz bildiriminiz yok.',

  'signed-out.parked.title': 'Çalışmanız kaydedildi',
  'signed-out.parked.body':
    'Oturumunuz bu gönderilmeden önce sona erdi. Hiçbir şey kaybolmadı — yeniden giriş yaptığınızda sizin için gönderilecek.',
  'signed-out.deliberate.title': 'Oturumunuz kapatıldı',
  'signed-out.deliberate.body':
    'Oturumunuz kapatıldı. İhtiyaç duyduğunuzda yeniden giriş yapabilirsiniz.',
  'signed-out.failed.title': 'Oturumunuz kapatıldı',
  'signed-out.failed.body':
    'Giriş tamamlanamadı. Yeniden deneyin; sorun sürerse eğitiminizi yöneten kişiye bildirin.',

  // ------------------------------------------------------------------------------ the loading
  'loading.label': 'Yükleniyor',
  'loading.session': 'Oturumunuz yükleniyor…',
  'loading.sign-in': 'Giriş sayfası açılıyor…',
  'loading.training': 'Eğitimleriniz yükleniyor…',
  'loading.progress': 'İlerlemeniz yükleniyor…',
  'loading.video': 'Video yükleniyor…',
  'loading.test': 'Sınavınız yükleniyor…',
  'loading.result': 'Sonucunuz yükleniyor…',
  'loading.console': 'Konsol yükleniyor…',
  'loading.roles': 'Roller yükleniyor…',
  'loading.role-editor': 'Rol düzenleyici yükleniyor…',
  'loading.course': 'Kurs yükleniyor…',
  'loading.courses': 'Kurslarınız yükleniyor…',
  'loading.assignments': 'Atamalar yükleniyor…',
  'loading.marking': 'Değerlendirme kuyruğu yükleniyor…',
  'loading.report': 'Rapor yükleniyor…',

  // ------------------------------------------------------------------------- failed and empty
  'error.label': 'Bir şeyler ters gitti',
  'error.reassurance': 'İlerlemeniz kaydedildi. Hiçbir şey kaybolmadı.',
  'error.retry': 'Yeniden dene',
  'empty.label': 'Burada bir şey yok',

  'api.unreachable': 'Servise ulaşılamadı.',
  'api.unreachable.detail': 'Servise ulaşılamadı: {reason}',
  'api.session-ended': 'Oturumunuz sona erdi. Devam etmek için yeniden giriş yapın.',
  'api.forbidden': 'Giriş yaptınız, ancak bu işlem size açık değil.',
  'api.not-found': 'Bulunamadı veya size görünür değil.',
  'api.status': 'Servis {status} yanıtı döndü.',

  // ----------------------------------------------------------------------- the eight states
  'state.due': 'Son tarih',
  'state.overdue': 'Gecikti',
  'state.in-progress': 'Devam ediyor',
  'state.locked': 'Kilitli',
  'state.awaiting': 'Değerlendirme bekliyor',
  'state.passed': 'Geçti',
  'state.not-passed': 'Geçemedi',
  'state.draft': 'Taslak',
  'state.published': 'Yayında',

  // ------------------------------------------------------------------------------------ home
  'home.welcome': 'Tekrar hoş geldiniz',
  'home.welcome.named': 'Tekrar hoş geldiniz, {name}',
  'home.continue': 'Öğrenmeye devam edin',
  'home.at-a-glance': 'Genel durum',
  'home.stat.assigned': 'Atanan',
  'home.stat.in-progress': 'Devam eden',
  'home.stat.completed': 'Tamamlanan',
  'home.stat.due-soon': 'Süresi yaklaşan',
  'home.stat.overdue': 'Geciken',
  'home.see-all': 'Tümünü gör',
  'home.done.title': 'Her şey tamamlandı.',
  'home.done.body':
    'Size atanan tüm eğitimleri bitirdiniz. Yeni bir eğitim atandığında burada görünür.',
  'discover.title': 'Keşfet',
  'discover.search': 'Eğitimlerinizde arayın',
  'discover.search.hint': 'Yalnızca size atanan eğitimlerde arar.',
  'discover.filter': 'Göster',
  'discover.filter.all': 'Tümü',
  'discover.filter.in-progress': 'Devam eden',
  'discover.filter.due-soon': 'Süresi yaklaşan',
  'discover.filter.overdue': 'Geciken',
  'discover.filter.complete': 'Tamamlanan',
  'discover.filter.locked': 'Kilitli bölümü var',
  'discover.count.one': '{count} eğitim',
  'discover.count.other': '{count} eğitim',
  'discover.modules.one': '{count} bölüm',
  'discover.modules.other': '{count} bölüm',
  'discover.empty.title': 'Bu aramaya uyan eğitim yok.',
  'discover.empty.body': 'Başka bir kelime deneyin veya filtreyi temizleyin.',
  'discover.clear': 'Filtreyi temizle',
  'discover.open': 'Aç',
  'discover.nothing-open': 'Bu eğitimde henüz açık bir bölüm yok',
  'home.due': 'Bekleyen',
  'home.next': 'Sırada',
  'home.courses': 'Kurslarınız',
  'home.start': 'Başla',
  'home.resume': 'Devam et',
  'home.resume-at': '{at} konumundan devam et',
  'home.stopped-at': '{at} konumunda bıraktınız',
  'home.progress-label': '{course}, yüzde {percent} tamamlandı',
  'home.this-course': 'Bu kurs',
  'home.complete': 'tamamlandı',
  'home.empty.title': 'Size atanmış bir eğitim yok.',
  'home.empty.body': 'Yöneticiniz bir eğitim atadığında burada görünür. Bugün yapılacak bir şey yok.',
  'home.locked.fallback-title': 'Sıradaki adım',
  'home.locked.fallback-reason': 'Bir önceki adım tamamlandığında açılır.',

  // -------------------------------------------------------------------------------- progress
  'progress.title': 'İlerlemeniz',
  'progress.empty.title': 'Size henüz bir eğitim atanmadı.',
  'progress.empty.body': 'İlerleme gösterilecek bir şey yok. Bugün yapılacak bir şey yok.',
  'progress.assigned': 'Atanan',
  'progress.completed': 'Tamamlanan',
  'progress.in-progress': 'Devam eden',
  'progress.due-soon': 'Süresi yaklaşan',
  'progress.overdue': 'Geciken',
  'progress.per-course': 'Eğitim bazında',
  'progress.course': 'Eğitim',
  'progress.done': 'Tamamlanan',
  'review.your-test': 'Sonucunuz',
  'review.question-number': '{n}. soru',
  'review.submitted': 'Gönderildi: {at}',
  'review.attempt-number': '{n}. deneme.',
  'review.just-now': 'az önce',
  'review.outcome.correct': 'Doğru',
  'review.outcome.not-correct': 'Doğru değil',
  'review.outcome.scored': '{points} üzerinden {awarded}',

  // ------------------------------------------------------------------------------- questions
  'question.your-answer': 'Cevabınız',
  'question.true': 'Doğru',
  'question.false': 'Yanlış',
  'question.unsupported':
    'Bu soru bir {type} sorusu ve bu ekran henüz onu gösteremiyor. Boş bırakın ve sınavı hazırlayan kişiye bildirin.',
  'question.move-earlier': '{item} ögesini yukarı taşı',
  'question.move-later': '{item} ögesini aşağı taşı',

  // ------------------------------------------------------------------ what is not enforced yet
  'not-enforced.label': 'Henüz kısıtlanmadı',
  'not-enforced.body':
    'Catalog ve assessment servisleri izinleri denetlemiyor (T-9.11). Bu şirkete giriş yapmış herkes, hangi role sahip olursa olsun burada içerik hazırlayabilir, atama yapabilir ve değerlendirebilir.',

  // ------------------------------------------------------------------------------ compliance
  'compliance.empty.title': 'Gösterilecek bir rapor yok.',
  'compliance.empty.body':
    '{service} servisi telemetri topluyor ancak henüz bir sorguyu yanıtlayamıyor. Bu ekranın dayandığı toplamalar T-7.1 – T-7.7 arasındadır.',
  'compliance.empty.note':
    'Burada gizlenen bir şey yok ve bir şey yüklenmiyor. Bu ekranda {count} kişilik bir şirket için örnek rakamlar vardı; uydurma oldukları için kaldırıldılar.',

  // ------------------------------------------------------------------------- the item shell
  'item.back': 'Geri',
  'item.position': 'adım {position}/{of}',
  'item.is-a': 'Bu öğe bir {type}',
  'item.type.video': 'Video',
  'item.type.scorm': 'SCORM',
  'item.type.slides': 'Slaytlar',
  'item.type.test': 'Sınav',
  'item.type.cmi5': 'cmi5',
  'item.type.unknown': 'Bölüm',
  'item.tabs': 'Bu bölüm',
  'item.tab.overview': 'Genel bakış',
  'item.tab.questions': 'Sorular',
  'item.tab.syllabus': 'İçindekiler',
  'item.syllabus': 'Eğitim içeriği',
  'item.syllabus.hide': 'Gizle',
  'item.syllabus.show': 'Göster',
  'item.overview.progress': 'İzlenen',
  'item.overview.state': 'Durum',
  'item.overview.required': 'Yükümlülük',
  'item.overview.is-required': 'Bu bölüm, eğitimi tamamlamak için zorunludur.',
  'item.overview.is-optional': 'Bu bölüm isteğe bağlıdır.',
  'item.not-in-your-training': 'Bu bölüm, size atanan eğitimlerde yok.',
  'item.not-in-your-training.body':
    'Atama kaldırılmış veya bağlantı güncelliğini yitirmiş olabilir. Hiçbir şey kaybolmadı.',
  'watch.questions.none': 'Bu videoya bağlı soru yok.',
  'watch.questions.none.body': 'Video baştan sona kesintisiz oynar.',
  'watch.questions.lead': 'Bu sorular, video oynarken görünür.',
  'watch.answered': 'yanıtlandı',

  'interstitial.bar': 'Videodaki soru · 1 / 1',
  'interstitial.foot':
    'Devam etmek için cevaplayın. Video {at} konumundan sürüyor — kaldığınız yer bu sekmede değil, sunucuda tutuluyor.',
  'interstitial.answer': 'Cevapla ve devam et',
  'answer.leave-blank': 'Bilmiyorum — boş bırak',

  // ----------------------------------------------------------------------------------- watch
  'watch.no-node': 'Adreste bir video belirtilmemiş.',
  'watch.frontier': 'Video {at} konumuna kadar oynar ve soru için orada bekler.',
  'watch.blocking': 'devam etmek için bunu cevaplamalısınız',

  // ---------------------------------------------------------------------------------- review
  'review.pass-mark': 'Geçme notu {mark}. Gönderim: {at}.',
  'review.score-only':
    'Bu sınav yalnızca puanınızı gösterir. Sorular ve cevaplar paylaşılmaz — bu bir hata değil, bu kursun kuralıdır.',
  'review.attempts': 'Hak',
  'review.attempts-used': '{allowed} haktan {used} tanesi kullanıldı.',
  'review.no-more-needed': 'Yeni bir hakka ihtiyacınız yok.',
  // Türkçede sayıdan sonra çoğul eki gelmez: iki karşılık da bilerek aynıdır.
  'review.with-a-person.one': '{count} cevap bir kişide bekliyor',
  'review.with-a-person.other': '{count} cevap bir kişide bekliyor',
  'review.awaiting-body':
    'Otomatik değerlendirilen her şey şu ana kadar doğru. Henüz bir sonuç yok — ne geçti ne kaldı. Yapmanız gereken bir şey yok.',
  'review.with-a-marker': 'Değerlendirmede',
  'review.marking-time':
    'Değerlendirme genellikle iki iş günü içinde tamamlanır. Sonucu burada bildireceğiz.',

  // ----------------------------------------------------------------------- sitting a test
  'sit.submitted.title': 'Gönderildi',
  'sit.submitted.body': 'Cevaplarınız alındı. Sizden başka bir şey beklenmiyor.',
  'sit.see-result': 'Sonucunuzu görün',
  'sit.where': 'Soru {position}/{of}',
  'sit.not-answered': '{count} soru cevaplanmadı',
  'sit.all-answered': 'tümü cevaplandı',
  'sit.back': 'Geri',
  'sit.next': 'İleri',
  'sit.all-answered-note': 'Her sorunun bir cevabı var.',
  'sit.blank-note.one': '{of} sorudan {count} tanesi hâlâ boş. Yine de gönderebilirsiniz.',
  'sit.blank-note.other': '{of} sorudan {count} tanesi hâlâ boş. Yine de gönderebilirsiniz.',
  'sit.submit': 'Gönder',
  'sit.no-time-limit': 'Süre sınırı yok',
  'sit.time-up': 'Süre doldu',
  'sit.time-left': '{at} kaldı',
  'sit.disclosure.title': 'Başlamadan önce',
  'sit.disclosure.we-record': 'Bu sınavı verirken şunları kaydediyoruz:',
  'sit.disclosure.nothing': 'Bu sınavı nasıl verdiğinize dair hiçbir şey kaydedilmiyor.',
  'sit.disclosure.kept.one': '{count} gün saklanır, sonra silinir.',
  'sit.disclosure.kept.other': '{count} gün saklanır, sonra silinir.',
  'sit.disclosure.start': 'Sınava başla',
  'sit.disclosure.not-now': 'Şimdi değil',

  // ---------------------------------------------------------------------------------- people
  'people.title': 'Kişiler',
  'people.email': 'E-posta adresi',
  'people.name': 'Görünen ad',
  'people.inviting': 'Davet ediliyor…',
  'people.invite': 'Davet et',
  'people.invited': '{name} ({email}) davet edildi. Davet {at} tarihinde sona eriyor.',
  'people.once.title': 'Bu bağlantı yalnızca bir kez gösterilir.',
  'people.once.body':
    'Yalnızca özetini saklıyoruz, bu yüzden sonradan görüntülenemez — şimdi gönderin ya da yeni bir bağlantı için kişiyi tekrar davet edin.',

  // --------------------------------------------------------------------------------- marking
  'grading.empty.title': 'Değerlendirme bekleyen bir şey yok.',
  'grading.empty.body':
    'Yazılı cevaplar ve yüklenen dosyalar, bir öğrenen gönderim yaptığında buraya düşer. Makinenin değerlendirebildiği her şey zaten değerlendirildi.',
  'grading.queue.title': 'Bir kişiyi bekliyor',
  'grading.queue.note':
    'Buradaki hiçbir kayıt üstlenilmiş değil — aynı denemeyi iki değerlendirici açabilir, uzun bir tanesine başlamadan önce kontrol edin.',
  'grading.col.test': 'Sınav',
  'grading.col.attempt': 'Deneme',
  'grading.col.outstanding': 'Bekleyen',
  'grading.col.waiting': 'Süre',
  'grading.mark': 'Değerlendir',
  'grading.attempt-heading': '{test} · deneme {number}',
  'grading.still-waiting': 'Aşağıdaki cevaplar hâlâ bekliyor.',
  'grading.verdict.passed': '{percent} — geçti',
  'grading.verdict.not-passed': '{percent} — geçemedi',
  'grading.marked': 'Değerlendirildi',
  'grading.not-marked': 'Değerlendirilmedi',
  'grading.out-of': 'toplam {available} puan',
  'grading.awarded': '{available} üzerinden {awarded}',
  'grading.award': 'Verilen puan',
  'grading.comment': 'Öğrenene not',
  'grading.range': '0 – {available}',

  // ------------------------------------------------------------------------------ assignment
  'assign.heading': 'Kurs ata',
  'assign.course': 'Kurs',
  'assign.choose': 'Seçin…',
  'assign.to': 'Kime',
  'assign.target.user': 'Bir kişiye',
  'assign.target.group': 'Bir gruba',
  'assign.target.tenant': 'Tüm şirkete',
  'assign.learner-id': 'Öğrenen kimliği',
  'assign.group-id': 'Grup kimliği',
  'assign.id-note':
    'Kimlik yazılır, çünkü identity servisinde henüz kişileri veya grup üyelerini listeleyen bir uç nokta yok.',
  'assign.due-on': 'Son tarih',
  'assign.due-note':
    'Son tarih sonradan değiştirilemez — catalog servisinde bir atamayı güncelleyen bir uç nokta yok. Değiştirmek, bu atamayı geri alıp yeniden atamak demektir.',
  'assign.no-identity':
    'Hesabınızın bu şirkette bir kimliği yok, bu yüzden sizin adınıza atama yapılamaz.',
  'assign.reach.tenant': 'bu şirketteki her etkin öğrenene',
  'assign.reach.group': 'o gruptaki ve altındaki tüm gruplardaki herkese',
  'assign.reach.user': 'tek bir kişiye',
  'assign.confirm': 'Bu işlem {course} kursunu {reach} atar.',
  'assign.confirm.yes': 'Evet, ata',
  'assign.confirm.cancel': 'Vazgeç',
  'assign.open': 'Ata…',
  'assign.empty.title': 'Bu şirkette atanmış bir şey yok.',
  'assign.empty.body': 'Bir şey atanana kadar her öğrenenin ana ekranı boş kalır.',
  'assign.existing': 'Atananlar',
  'assign.col.pinned': 'Sabitlenen sürüm',
  'assign.to.tenant': 'tüm şirket',
  'assign.uuid': 'uuid',
  'assign.to.user': 'kullanıcı {id}',
  'assign.to.group': 'grup {id}',
  'assign.revoke': 'Geri al',
  'assign.behind': 'geride',

  // ------------------------------------------------------------------------------- authoring
  'authoring.no-course.title': 'Açık kurs yok.',
  'authoring.no-course.body': 'Soldan birini seçin veya ilkini oluşturun.',
  'authoring.courses': 'Kurslar',
  'authoring.none-yet': 'Henüz yok. İlki aşağıda oluşturulur.',
  'authoring.new-course': 'Yeni kurs',
  'authoring.new-course.placeholder': 'Yangın Güvenliği Tazeleme',
  'authoring.no-rename': 'Bir kurs oluşturulduktan sonra adı değiştirilemez.',
  'authoring.create': 'Oluştur',
  'authoring.publish-version': 'Sürüm yayımla',
  'authoring.version': 'Sürüm {version}',
  'authoring.published': 'Yayımlandı',
  'authoring.no-modules.title': 'Bu kursta henüz modül yok.',
  'authoring.no-modules.body': 'Bir modül, öğrenenin sırayla ilerlediği adımları tutar.',
  'authoring.required': 'zorunlu',
  'authoring.optional': 'isteğe bağlı',
  'authoring.drafts.title': 'Henüz yayımlanmadı',
  'authoring.drafts.body':
    'Bir kurs taslak içeriğe bağlanamaz. Buradan yayımlayın, ardından adım olarak eklenebilir.',
  'authoring.publish': 'Yayımla',
  'authoring.add-module': 'Modül ekle',
  'authoring.add-module.placeholder': 'Modül 1 · Başlangıç',
  'authoring.add': 'Ekle',
  'authoring.add-node': 'Adım ekle',
  'authoring.choose-content': 'İçerik seçin…',
  'authoring.new-item': 'Yeni içerik ögesi',
  'authoring.type': 'Tür',
  'authoring.choose': 'Seçin…',
  'authoring.title': 'Başlık',
  'authoring.reference': 'referans',
  'authoring.reference.placeholder': 'bu ögenin işaret ettiği kimlik',
  'authoring.points-at':
    'İçerik bir şeyi tutmaz, ona işaret eder: bir videonun baytları streaming servisinde, bir sınav assessment servisinde durur.',

  // ----------------------------------------------------------------------------------- roles
  'roles.half-missing.label': 'Bunun yarısı eksik',
  'roles.half-missing.body':
    'Roller gerçek. Seçilebilecek izinlerin listesi ise hiçbir uç nokta tarafından yayımlanmıyor — identity servisindeki {enum} enum’unda duruyor ve entegrasyon yazanlara yalnızca API açıklamasındaki bir tablo olarak ulaşıyor. Kodlar buraya elle yazılır ve sunucu tarafından doğrulanır.',
  'roles.title': 'Roller',
  'roles.empty.title': 'Bu şirkette hiç rol yok.',
  'roles.empty.body': 'Hazır gelen roller şirketle birlikte oluşturulur.',
  'roles.system': 'sistem',
  'roles.permission-count.one': '{count} izin',
  'roles.permission-count.other': '{count} izin',
  'roles.holds-nothing': 'Bu rol hiçbir şey tutmuyor, dolayısıyla hiçbir şey vermiyor.',
  'roles.remove': 'Kaldır',
  'roles.seeded': 'Hazır gelen bir rol. Her şirketin aldığı rolü değiştirmek yerine kopyalayın.',
  'roles.add-permission': 'İzin ekle',
  'roles.add': 'Ekle',
  'roles.code-placeholder': 'resource:action',

  // ---------------------------------------------------------------------------- the player
  'player.untitled': 'Video',
  'player.no-node': 'Bu oynatıcı, oynatılacak bir video olmadan açıldı.',
};

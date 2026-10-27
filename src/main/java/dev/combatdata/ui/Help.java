package dev.combatdata.ui;

import static dev.combatdata.core.Lang.t;

/** Methods rather than constants so the text follows language switches. */
final class Help {
    private Help() {
    }

    static String overview() {
        return t("Выводы сверху: самое важное, что видно по записи. Клик по выводу открывает нужный график. Ниже карточки с цифрами, «Подробнее» раскрывает остальное.",
                "Insights on top: the most important things in this recording. Click one to open the right chart. Below are cards with numbers, \"More\" shows the rest.");
    }

    static String hits() {
        return t("Точность: сколько ваших атак сервер подтвердил уроном.\n«Не дотянулся»: прицел был на противнике, но он был дальше вашей дальности.\n«Без подтверждения»: удар ушёл, но урона не было (лаг, щит или неуязвимость после удара).",
                "Accuracy: how many of your attacks the server confirmed with damage.\n\"Out of range\": your crosshair was on them, but they were beyond your reach.\n\"Unconfirmed\": the hit was sent but no damage came (lag, shield or hurt immunity).");
    }

    static String jump() {
        return t("Jump reset: прыжок сразу после полученного удара гасит часть отбрасывания.\nУспех: прыжок попал в окно после удара (окно в Настройки → Дополнительно). Рано или поздно: прыжок был, но мимо окна.\nFly out: прыжок с зажатой S, чтобы улететь дальше. В jump reset не считается.",
                "Jump reset: jumping right after you get hit cancels part of the knockback.\nSuccess: the jump landed in the window after the hit (window in Settings → Advanced). Early or late: you jumped but missed the window.\nFly out: a jump while holding S to fly further. Not counted as a jump reset.");
    }

    static String reach() {
        return t("Расстояние от ваших глаз до места, куда попал удар. «Дальше дальности»: удар засчитан, хотя на вашем экране противник был дальше вашей дальности атаки (так бывает из-за пинга).",
                "Distance from your eyes to the spot the hit landed. \"Past range\": the hit counted although on your screen they were beyond your attack range (ping does that).");
    }

    static String aim() {
        return t("Насколько прицел отклонялся от центра противника при ударе. Главное — по горизонтали: по вертикали это скорее выбор, куда бить (голова или тело). Удары вплотную, ближе 1 блока, не считаются.",
                "How far your crosshair was from the center of the opponent when you hit. Horizontal matters most: vertical is mostly where you choose to hit (head or body). Point-blank hits closer than 1 block are skipped.");
    }

    static String threeBlock() {
        return t("3 block hit: ваш удар засчитался, хотя на экране противника вы были дальше 3 блоков. Из-за пинга он видит вас с опозданием, и если вы шли к нему (или он от вас), ответить в этот момент он не мог. Это оценка по скоростям и пингу.",
                "3 block hit: your hit counted although on the opponent's screen you were beyond 3 blocks. Ping makes them see you late, and if you were moving in (or they were moving away) they could not hit back at that moment. Estimated from speeds and ping.");
    }

    static String combo() {
        return t("Комбо: сколько ваших ударов прошло подряд, пока противник не ударил в ответ. Размен (нанесено к получено) больше 1 значит, что вы били чаще.",
                "Combo: how many of your hits landed in a row before the opponent hit back. A trade ratio (dealt to taken) above 1 means you hit more often.");
    }

    static String trades() {
        return t("Размен: короткий обмен ударами с одним противником. Новый начинается, если ударов не было дольше 1,5 с. Побеждает тот, у кого больше ударов.\nВ скобках счёт по весу: крит 1,5, ваш слабый удар меньше 1.\nПричина проигрыша: что было перед его первым ударом (ваш промах, удар без заряда, 3 block hit против вас или неудачный jump reset).",
                "Trade: a short exchange of hits with one opponent. A new one starts after more than 1.5 s without hits. Whoever lands more hits wins.\nIn brackets is the score by weight: a crit is 1.5, your weak hit is less than 1.\nLoss cause: what happened before their first hit (your miss, an uncharged hit, a 3 block hit against you or a failed jump reset).");
    }

    static String charge() {
        return t("Заряд: насколько восстановилась атака после прошлого взмаха. Выше 90% удар полный: больше урона, и только такой удар бывает с разбега или критом. Слишком частые клики бьют без заряда.",
                "Charge: how much your attack recovered since the last swing. Above 90% the hit is full: more damage, and only a full hit can be a sprint hit or a crit. Clicking too fast hits without charge.");
    }

    static String types() {
        return t("С разбега: полный удар на бегу, сильнее отбрасывает.\nКрит: удар в падении после прыжка, урон × 1,5.\nСлабый: без полного заряда.\nРазящий: полный удар мечом стоя, задевает тех, кто рядом.",
                "Sprint: a full hit while sprinting, more knockback.\nCrit: a hit while falling after a jump, damage × 1.5.\nWeak: not fully charged.\nSweep: a full sword hit while standing, also hits those nearby.");
    }

    static String ping() {
        return t("Ваш пинг до сервера во время записи. Скачки пинга искажают jump reset и 3 block hit.",
                "Your ping to the server during the recording. Ping spikes distort jump reset and 3 block hit.");
    }

    static String taken() {
        return t("С какого расстояния противник бил вас, как это было видно на вашем экране. 3 block hit против вас: он ударил, когда был дальше вашей дальности, и ответить вы не могли.",
                "How far the opponent was when they hit you, as seen on your screen. 3 block hit against you: they hit while beyond your reach, so you could not hit back.");
    }

    static String wtap() {
        return t("После удара с разбега спринт сбрасывается. Если клавиша спринта зажата, он вернётся сам через тик. Если нет, спринт надо вернуть: отпустить и снова нажать W (W-tap) или коротко нажать S (S-tap). Без спринта следующий удар отбрасывает слабее.",
                "A sprint hit resets your sprint. If you hold the sprint key, it comes back a tick later by itself. If not, you have to bring it back: release and press W again (W-tap) or tap S (S-tap). Without sprint the next hit knocks back less.");
    }

    static String hitsChart() {
        return t("Каждая точка: ваша атака, по высоте дистанция удара. Зелёные попали, жёлтые 3 block hit, красные промах, серые без подтверждения. Пунктир: ваша дальность.\nКолесо: масштаб, перетаскивание: сдвиг, клик по точке: подробности.",
                "Each dot is your attack, height is the hit distance. Green hit, yellow 3 block hit, red miss, grey unconfirmed. Dashed line: your range.\nWheel: zoom, drag: pan, click a dot: details.");
    }

    static String aimTab() {
        return t("Карта: где был прицел на каждом замахе. Зелёные точки попадания, красные промахи прицелом, синие «не дотянулся» (прицел был на противнике, но дальше дальности). Наведите на точку, чтобы увидеть подробности.\nТаблицы: точность против стрейфящих (от 2 бл/с вбок) и прыгающих противников, при резких доводках (от 180°/с), в воздухе и по дистанции. Советы сверху собраны по этим данным; условие отмечается, когда точность в нём хуже средней на 15% и больше.\nУдары ближе 1 блока не считаются.",
                "Map: where the crosshair was on every swing. Green dots are hits, red are aim misses, blue are out of range (the crosshair was on the opponent but beyond reach). Hover a dot for details.\nTables: accuracy against strafing (2+ bl/s sideways) and jumping opponents, on fast flicks (180°/s+), in the air and by distance. The tips on top come from this data; a condition is called out when its accuracy is 15 points or more below average.\nSwings closer than 1 block are not counted.");
    }

    static String jumpChart() {
        return t("Каждая точка: удар по вам. По высоте: через сколько мс после него вы прыгнули (0 — момент удара, минус — до удара). Зелёная полоса: окно успеха. Метки внизу: удары без прыжка.",
                "Each dot is a hit on you. Height: how many ms after it you jumped (0 is the hit, minus is before it). Green band: the success window. Ticks at the bottom: hits without a jump.");
    }

    static String knockback() {
        return t("Насколько вас отбросило за полсекунды после удара в зависимости от момента прыжка. Самые низкие столбцы: лучшее время для прыжка на этом сервере и пинге. По ним можно подобрать окно jump reset.",
                "How far you were knocked back in half a second depending on when you jumped. The lowest bars are the best time to jump on this server and ping. The jump reset window can be fitted from them.");
    }

    static String opponents() {
        return t("Клик по строке: вся статистика только по этому противнику. Ещё клик или × на чипе снимает фильтр.",
                "Click a row: all stats for this opponent only. Click again or × on the chip to clear.");
    }

    static String fights() {
        return t("Бой: удары без паузы больше 10 с. Клик по бою открывает его размены. Совсем короткие стычки скрываются (Настройки → Фильтры статистики).",
                "A fight is hits without a pause over 10 s. Click a fight to see its trades. Very short skirmishes are hidden (Settings → Stat filters).");
    }

    static String distance() {
        return t("Линия: расстояние до противника во время боя (от ваших глаз до его тела). Отметки: удары, ваши зелёные, его красные, жёлтые — 3 block hit. Пунктир: ваша дальность.\n«Повтор» показывает бой сверху; клик по графику переносит повтор в этот момент.",
                "Line: distance to the opponent during the fight (from your eyes to their body). Marks are hits: yours green, theirs red, yellow are 3 block hits. Dashed line: your range.\n\"Replay\" shows the fight from above; click the chart to jump the replay there.");
    }

    static String timeline() {
        return t("Бои, метки и смены сервера по порядку.", "Fights, markers and server changes in order.");
    }

    static String hidden() {
        return t("Эти удары не входят в статистику, но остаются в записи. Что отсекать, задаётся в Настройки → Фильтры статистики. «Показать» включает их только для этой записи.",
                "These hits are left out of the stats but stay in the recording. What gets filtered is set in Settings → Stat filters. \"Show\" brings them back for this recording only.");
    }

    static String progress() {
        return t("Каждая точка: запись или день. Точки идут по порядку через равные промежутки, даты снизу. Точка появляется, только если данных хватает (например, от 5 атак). Пунктир: среднее. Клик по точке открывает запись.",
                "Each dot is a recording or a day, evenly spaced in order, dates below. A dot appears only with enough data (e.g. 5+ attacks). Dashed line: the average. Click a dot to open the recording.");
    }

    static String interp() {
        return t("Игра противника показывает вас чуть в прошлом, чтобы движение было плавным. Обычно около 100 мс. Нужно только для оценки 3 block hit.",
                "The opponent's game shows you slightly in the past to keep movement smooth. Usually about 100 ms. Only used to estimate 3 block hits.");
    }

    static String calibrate() {
        return t("Ищет момент прыжка, при котором вас меньше всего отбрасывало, по всем сохранённым записям. Ширина окна остаётся вашей, сдвигается только его положение.",
                "Finds the jump timing that gave you the least knockback across all saved recordings. Your window width stays, only its position moves.");
    }

    static String replay() {
        return t("Бой сверху: вы зелёный квадрат, противник красный, остальные серые (размер как хитбокс). Чёрточка показывает, куда смотрит игрок, хвост — путь за секунду. Сетка — блоки, каждая пятая линия ярче.\nВ прыжке игрок поднимается над тенью и обводится светлой рамкой. После удара по вам над вами ярлык: «JR ✔» — jump reset в окне, «рано»/«поздно» — мимо, «fly out», «без прыжка».\nВспышка — удар (жёлтая — 3 block hit), круг — ваша дальность. Колесо над видом — масштаб, ползунок перематывает, ‹ и › — шаг на тик.",
                "The fight from above: you are the green square, the opponent red, others grey (hitbox size). The tick shows where a player looks, the tail is the last second. The grid is blocks, every fifth line brighter.\nIn a jump a player rises above their shadow with a light outline. After a hit on you a tag shows above you: \"JR ✔\" is a jump reset in the window, \"early\"/\"late\" missed it, \"fly out\", \"no jump\".\nA flash is a hit (yellow is a 3 block hit), the ring is your range. Wheel over the view zooms, the slider seeks, ‹ and › step one tick.");
    }

    static String recordMode() {
        return t("Полная: пишется всё. Лёгкая: без позиций, нажатий клавиш и отлёта. Своя: вы сами выбрали, что писать.\nЗаметно место экономят только позиции (около 0,7 МБ на 20 минут боя), остальное мелочь: его выключают, чтобы не было лишних экранов.\nВыключенное не попадает в новые записи, и в Просмотре такие экраны пишут «не записывалось». Старые записи не меняются.",
                "Full: everything is recorded. Lite: no positions, key presses or knockback. Custom: you picked what to record.\nOnly positions take noticeable space (about 0.7 MB per 20 minutes of fighting), the rest is tiny: turn it off to hide screens you do not use.\nWhat is off is not saved in new recordings, and those screens in View say \"not recorded\". Old recordings do not change.");
    }

    static String fightSummary() {
        return t("После боя (по умолчанию через 10 секунд без ударов, на 5 секунд) рядом с надписью REC появляется итог: попадания, jump reset, лучшее комбо, размены (выиграно:проиграно); по желанию дистанция, 3 block hit и полученные удары. Во время боя ничего не показывается. Учитываются фильтры статистики, бои меньше минимального размера не показываются.\nНиже настраивается, что показывать, вид, цвета, фон, масштаб, время и место; меняется сразу, пример показывает, как это выглядит. Когда бой считается оконченным, задаётся в Настройки → Основные.",
                "After a fight (by default after 10 seconds without hits, for 5 seconds) a summary shows next to the REC label: hits, jump reset, best combo, trades (won:lost); optionally reach, 3 block hits and hits taken. Nothing shows during a fight. Stat filters apply, fights below the minimum size are skipped.\nBelow you choose what to show, layout, colors, background, scale, timing and place; changes apply at once and the example shows how it looks. When a fight counts as over is set in Settings → General.");
    }

    static String themes() {
        return t("Тема меняет цвета меню. Выбор видно сразу, сохраняется по «Применить».\nВстроенные темы не меняются: «Создать копию» делает свою, у неё можно поменять каждый цвет (клик по цвету: ползунки, клик по коду: ввести hex) и название.\n«Копировать код» кладёт тему в буфер обмена строкой, её можно отправить другу; «Вставить код» добавляет тему из буфера обмена.",
                "A theme changes the menu colors. You see the choice right away, it is saved on Apply.\nBuilt-in themes do not change: \"Make a copy\" creates your own, where every color (click a color: sliders, click its code: type hex) and the name can be changed.\n\"Copy code\" puts the theme on the clipboard as text you can send to a friend; \"Paste code\" adds a theme from the clipboard.");
    }
}

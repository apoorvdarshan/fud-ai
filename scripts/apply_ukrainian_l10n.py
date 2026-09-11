#!/usr/bin/env python3
"""Generate Ukrainian Android resources and add uk localizations to iOS xcstrings."""
from __future__ import annotations

import copy
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_EN = ROOT / "android/app/src/main/res/values"
ANDROID_UK = ROOT / "android/app/src/main/res/values-uk"
IOS_DIR = ROOT / "ios/calorietracker"
CONTRIB_UK = Path("/tmp/uk_contrib.xml")
SCRIPTS = Path(__file__).resolve().parent

UK_BARCODE = {
    "error_barcode_invalid": "Не вдалося прочитати цей штрихкод. Спробуйте відсканувати його ще раз.",
    "error_barcode_product_not_found": "Продукт не знайдено в Open Food Facts. Натомість відскануйте етикетку з харчовою цінністю.",
    "error_barcode_missing_nutrition": "Штрихкод знайдено, але дані про харчову цінність неповні. Натомість відскануйте етикетку.",
    "error_barcode_rate_limited": "Здійснено занадто багато пошуків штрихкодів. Зачекайте трохи та спробуйте знову.",
    "error_barcode_service_unavailable": "Open Food Facts тимчасово недоступний. Спробуйте пізніше.",
    "error_barcode_unexpected_response": "Open Food Facts повернув неочікувану відповідь.",
    "error_barcode_network": "Не вдалося підключитися до Open Food Facts. Перевірте з'єднання та спробуйте знову.",
    "error_barcode_offline": "Схоже, ви офлайн. Перевірте з'єднання та спробуйте відсканувати знову.",
    "error_barcode_timeout": "Open Food Facts занадто довго не відповідав. Спробуйте відсканувати знову.",
}

UK_WEEKLY = {
    "challenge_destination_progress": "Мій прогрес",
    "challenge_destination_weekly": "Щотижневе змагання",
    "challenge_title": "Щотижневе змагання",
    "challenge_week_range": "%1$s – %2$s",
    "challenge_last_updated": "Останнє оновлення: %1$s",
    "challenge_offline_status": "Офлайн · показано останній збережений рейтинг",
    "challenge_not_updated": "Ще не оновлено",
    "challenge_intro_title": "Формуйте корисні звички разом",
    "challenge_intro_body": "За бажанням приєднуйтесь, щоб порівнювати щотижневі досягнення в активності, харчуванні, регулярності та водному балансі. Ваш приватний екран «Прогрес» залишається доступним без участі.",
    "challenge_privacy_disclosure": "Fud AI виконує всі розрахунки на пристрої. Завантажуються лише дата початку тижня, загальні бали, кількість зарахованих днів активності, харчування, регулярності та водного балансу, а також сумарні калорії активності. Назви продуктів, прийоми їжі, вага, деталі тренувань і записи Health Connect ніколи не завантажуються.",
    "challenge_public_profile_disclosure": "Учасникам видно лише ваше відображуване ім'я та, за бажанням, одне посилання на X або Instagram. Вага та її зменшення ніколи не оцінюються.",
    "challenge_points_title": "Як нараховуються бали",
    "challenge_points_explanation": "Активність: день із додатним записаним витратом калорій на тренуванні. Харчування: добові калорії в межах 85–115% цілі. Регулярність: у цей день записано будь-яку їжу. Водний баланс: ціль з води досягнута за увімкненого обліку. Загальний залік: сума чотирьох кількостей днів, максимум 28.",
    "challenge_join_action": "Приєднатися",
    "challenge_pending_delete_title": "Видалення очікує з'єднання",
    "challenge_pending_delete_message": "Профіль змагання приховано на цьому пристрої. Fud AI повторить видалення віддаленого профілю та щотижневих результатів, коли з'явиться інтернет.",
    "challenge_retry_deletion": "Повторити видалення",
    "challenge_category_overall": "Загальний залік",
    "challenge_category_activity": "Активність",
    "challenge_category_nutrition": "Харчування",
    "challenge_category_consistency": "Регулярність",
    "challenge_category_hydration": "Водний баланс",
    "challenge_my_position": "Моя позиція",
    "challenge_rank_format": "№ %1$d",
    "challenge_unranked": "Поза рейтингом",
    "challenge_points_format": "%1$d з 28 балів",
    "challenge_days_format": "%1$d з 7 днів",
    "challenge_days_kcal_format": "%1$d з 7 днів · %2$d ккал",
    "challenge_weekly_breakdown": "Цей тиждень",
    "challenge_edit_profile": "Редагувати профіль",
    "challenge_leave_action": "Вийти",
    "challenge_leaderboard_title": "Рейтинг",
    "challenge_manage_blocked": "Керування блокуваннями",
    "challenge_leaderboard_empty": "Інших учасників у рейтингу поки немає. Потягніть вниз, щоб оновити.",
    "challenge_more_actions": "Інші дії для %1$s",
    "challenge_report_action": "Поскаржитися",
    "challenge_block_action": "Заблокувати",
    "challenge_open_social": "Відкрити @%1$s у %2$s",
    "challenge_social_display": "@%1$s · %2$s",
    "challenge_join_title": "Приєднатися до щотижневого змагання",
    "challenge_edit_title": "Редагувати профіль змагання",
    "challenge_display_name_label": "Відображуване ім'я",
    "challenge_display_name_help": "2–40 символів: літери, цифри, пробіли, крапка, підкреслення, апостроф або дефіс",
    "challenge_display_name_error": "Використовуйте 2–40 дозволених символів. Посилання, електронні адреси, офіційні/адміністративні імена, видавання себе за інших і небезпечні терміни заборонені.",
    "challenge_social_label": "Необов'язковий профіль у соцмережі (оберіть один)",
    "challenge_social_none": "Немає",
    "challenge_social_x": "X",
    "challenge_social_instagram": "Instagram",
    "challenge_handle_label": "Ім'я користувача",
    "challenge_handle_help": "Введіть лише ім'я користувача, без @ і посилання.",
    "challenge_x_handle_error": "Використовуйте 1–15 літер, цифр або підкреслень.",
    "challenge_instagram_handle_error": "Використовуйте 1–30 літер, цифр, крапок або підкреслень; без крапок підряд або наприкінці.",
    "challenge_rules_consent": "Я приймаю Правила спільноти та використовуватиму поважний профіль, не видаючи себе за інших.",
    "challenge_read_community_rules": "Прочитати Правила спільноти",
    "challenge_age_consent": "Я підтверджую, що мені виповнилося 18 років. Fud AI не отримує і не завантажує мою дату народження.",
    "challenge_leave_title": "Вийти зі змагання?",
    "challenge_leave_message": "Віддалений профіль змагання та всі щотижневі результати будуть видалені. Особисті дані про їжу, вагу, тренування та Health Connect залишаться на пристрої.",
    "challenge_leave_confirm": "Вийти та видалити",
    "challenge_report_title": "Поскаржитися на %1$s",
    "challenge_report_privacy": "Скарга містить лише ID учасника, обрану причину та необов'язкові деталі — ваші дані про здоров'я не включаються.",
    "challenge_report_inappropriate_name": "Недопустиме ім'я",
    "challenge_report_impersonation": "Видає себе за іншого",
    "challenge_report_spam": "Спам",
    "challenge_report_unsafe_content": "Небезпечний вміст",
    "challenge_report_other": "Інше",
    "challenge_report_details": "Необов'язкові деталі",
    "challenge_character_count": "%1$d з %2$d символів",
    "challenge_report_submit": "Надіслати скаргу",
    "challenge_report_sent_title": "Скаргу надіслано",
    "challenge_report_sent_message": "Дякуємо. Скаргу на %1$s надіслано на розгляд.",
    "challenge_block_title": "Заблокувати %1$s?",
    "challenge_block_message": "Цей учасник буде прихований із рейтингу на цьому пристрої. Йому не надсилається сповіщення.",
    "challenge_blocked_title": "Заблоковані учасники",
    "challenge_blocked_empty": "Ви нікого не заблокували.",
    "challenge_unblock_action": "Розблокувати",
    "challenge_error_network": "Схоже, немає з'єднання. Збережений рейтинг і надалі доступний.",
    "challenge_error_auth": "Сесію змагання завершено. Приєднайтеся знову, щоб створити нову сесію.",
    "challenge_error_server": "Не вдалося оновити щотижневе змагання. Спробуйте незабаром.",
    "challenge_error_validation": "Перевірте відображуване ім'я, профіль у соцмережі та обов'язкові підтвердження.",
}


def parse_android_resources(path: Path) -> dict[str, str | dict[str, str]]:
    tree = ET.parse(path)
    root = tree.getroot()
    result: dict[str, str | dict[str, str]] = {}
    for child in root:
        tag = child.tag.split("}")[-1]
        name = child.get("name")
        if tag == "string":
            result[name] = child.text or ""
        elif tag == "plurals":
            result[name] = {
                item.get("quantity"): item.text or "" for item in child.findall("item")
            }
    return result


def escape_xml(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace('"', "&quot;")
    )


PLURAL_OVERRIDES: dict[str, dict[str, str]] = {
    "progress_workout_history_count_format": {
        "one": "%1$d розрахований витрат · торкніться, щоб переглянути або видалити",
        "other": "%1$d розрахованих витрат · торкніться, щоб переглянути або видалити",
    },
    "progress_history_count_format": {
        "one": "%1$d запис · торкніться, щоб переглянути або видалити",
        "other": "%1$d записів · торкніться, щоб переглянути або видалити",
    },
    "exercises_count": {
        "one": "%d вправа",
        "other": "%d вправ",
    },
    "copy_foods_to": {
        "one": "Скопіювати %1$d страву до %2$s",
        "other": "Скопіювати %1$d страв до %2$s",
    },
    "import_add_meals_title": {
        "one": "Додати спільну страву",
        "other": "Додати %1$d спільних страв",
    },
}


def load_uk_map() -> dict[str, str | dict[str, str]]:
    contrib = parse_android_resources(CONTRIB_UK) if CONTRIB_UK.exists() else {}
    supplement = json.loads((SCRIPTS / "uk_android_supplement.json").read_text(encoding="utf-8"))
    merged = dict(contrib)
    merged.update(supplement)
    merged.update(PLURAL_OVERRIDES)
    # Drop obsolete contributor keys
    for obsolete in ("about_follow_instagram", "about_support"):
        merged.pop(obsolete, None)
    return merged


def build_en_text_to_uk(uk_map: dict) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for xml_name, uk_by_name in (
        ("strings.xml", uk_map),
        ("barcode_errors.xml", UK_BARCODE),
        ("weekly_challenge.xml", UK_WEEKLY),
    ):
        en = parse_android_resources(ANDROID_EN / xml_name)
        for name, en_val in en.items():
            uk_val = uk_by_name.get(name)
            if uk_val is None:
                continue
            if isinstance(en_val, str) and isinstance(uk_val, str):
                mapping[en_val] = uk_val
    ios_supp = json.loads((SCRIPTS / "uk_ios_supplement.json").read_text(encoding="utf-8"))
    mapping.update(ios_supp)
    return mapping


RU_TO_UK_PHRASES: list[tuple[str, str]] = [
    ("Щёлкните", "Натисніть"),
    ("щёлкните", "натисніть"),
    ("Нажмите", "Натисніть"),
    ("нажмите", "натисніть"),
    ("Настройки", "Налаштування"),
    ("настройки", "налаштування"),
    ("Не удалось", "Не вдалося"),
    ("не удалось", "не вдалося"),
    ("Попробуйте", "Спробуйте"),
    ("попробуйте", "спробуйте"),
    ("Повторите попытку", "Спробуйте знову"),
    ("повторите попытку", "спробуйте знову"),
    ("Удалить", "Видалити"),
    ("удалить", "видалити"),
    ("Загружен", "Завантажен"),
    ("загружен", "завантажен"),
    ("Загрузка", "Завантаження"),
    ("загрузка", "завантаження"),
    ("Не загружен", "Не завантажен"),
    ("не загружен", "не завантажен"),
    ("Используется", "Використовується"),
    ("используется", "використовується"),
    ("Требует внимания", "Потребує уваги"),
    ("Подготовить", "Підготувати"),
    ("Подготовка", "Підготовка"),
    ("Проверка", "Перевірка"),
    ("Проверя", "Перевір"),
    ("проверя", "перевір"),
    ("этого iPhone", "цього iPhone"),
    ("Этого iPhone", "Цього iPhone"),
    ("на этом iPhone", "на цьому iPhone"),
    ("На этом iPhone", "На цьому iPhone"),
    ("с этого iPhone", "з цього iPhone"),
    ("С этого iPhone", "З цього iPhone"),
    ("Подключение", "З'єднання"),
    ("подключение", "з'єднання"),
    ("Подождите", "Зачекайте"),
    ("подождите", "зачекайте"),
    ("Сохранить", "Зберегти"),
    ("сохранить", "зберегти"),
    ("Сохраняет", "Зберігає"),
    ("сохраняет", "зберігає"),
    ("медиатеку", "медіатеку"),
    ("Медиатеку", "Медіатеку"),
    ("фото блюд", "фото страв"),
    ("продуктов", "продуктів"),
    ("продукты", "продукти"),
    ("штрихкод", "штрихкод"),
    ("Штрихкод", "Штрихкод"),
    ("Поставщики ИИ", "Провайдери ШІ"),
    ("поставщики ИИ", "провайдери ШІ"),
    ("ИИ", "ШІ"),
    ("модель", "модель"),
    ("Модель", "Модель"),
    ("Готово", "Готово"),
    ("готово", "готово"),
    ("офлайн", "офлайн"),
    ("Офлайн", "Офлайн"),
    ("Компиляция", "Компіляція"),
    ("компиляция", "компіляція"),
    ("Локальная", "Локальна"),
    ("локальная", "локальна"),
    ("генерация", "генерація"),
    ("Генерация", "Генерація"),
    ("Дождитесь", "Зачекайте"),
    ("дождитесь", "зачекайте"),
    ("занята", "зайнята"),
    ("Занята", "Зайнята"),
    ("Ход загрузки", "Прогрес завантаження"),
    ("Проверка загрузки", "Перевірка завантаження"),
    ("удалены", "видалені"),
    ("Удалены", "Видалені"),
    ("скомпилированный", "скомпільований"),
    ("выполнения", "виконання"),
    ("среды", "середовища"),
    ("конфиденциальной", "конфіденційної"),
    ("обработке", "обробці"),
    ("изображений еды", "зображень їжі"),
    ("требуется", "потрібен"),
    ("Требуется", "Потрібен"),
    ("памяти", "пам'яті"),
    ("Памяти", "Пам'яті"),
    ("Размер загрузки", "Розмір завантаження"),
    ("свободного места", "вільного місця"),
    ("Проверено и сохранено локально", "Перевірено та збережено локально"),
    ("Нажмите «Подготовить»", "Натисніть «Підготувати»"),
    ("загрузится при первом использовании", "завантажиться під час першого використання"),
    ("Загрузка закреплённой", "Завантаження закріпленої"),
    ("Не закрывайте", "Не закривайте"),
    ("до завершения", "до завершення"),
    ("Проверка точного", "Перевірка точного"),
    ("перед установкой", "перед встановленням"),
    ("найти штрихкод", "знайти штрихкод"),
    ("Найти штрихкод", "Знайти штрихкод"),
    ("запросов штрихкода", "запитів штрихкода"),
    ("Слишком много", "Занадто багато"),
    ("сканирования", "сканування"),
    ("использует камеру", "використовує камеру"),
    ("использует", "використовує"),
    ("для сканирования", "для сканування"),
]


def convert_ru_to_uk(text: str) -> str:
    result = text
    for ru, uk in sorted(RU_TO_UK_PHRASES, key=lambda p: -len(p[0])):
        result = result.replace(ru, uk)
    # Character-level fallbacks for common East Slavic divergences
    repl = {
        "ы": "и",
        "Ы": "И",
        "э": "е",
        "Э": "Е",
        "ъ": "",
        "Ъ": "",
    }
    for ru, uk in repl.items():
        result = result.replace(ru, uk)
    return result


def build_ru_text_to_uk(uk_map: dict) -> dict[str, str]:
    en = parse_android_resources(ANDROID_EN / "strings.xml")
    ru_path = ROOT / "android/app/src/main/res/values-ru/strings.xml"
    mapping: dict[str, str] = {}
    if ru_path.exists():
        ru = parse_android_resources(ru_path)
        for name, ru_val in ru.items():
            if name not in uk_map:
                continue
            uk_val = uk_map[name]
            if isinstance(ru_val, str) and isinstance(uk_val, str) and ru_val.strip():
                mapping[ru_val] = uk_val

    en_to_uk = build_en_text_to_uk(uk_map)
    for path in IOS_DIR.glob("*.xcstrings"):
        data = json.loads(path.read_text(encoding="utf-8"))
        for key, entry in data.get("strings", {}).items():
            locs = entry.get("localizations", {}) if entry else {}
            ru_val = get_ru_value(entry or {})
            if not ru_val:
                continue
            en_val = get_en_value(key, entry or {})
            uk_val = en_to_uk.get(en_val) or en_to_uk.get(key)
            if uk_val:
                mapping[ru_val] = uk_val
    return mapping


def generate_android_strings_xml(uk_map: dict[str, str | dict[str, str]]) -> None:
    en_text = (ANDROID_EN / "strings.xml").read_text(encoding="utf-8")
    ANDROID_UK.mkdir(parents=True, exist_ok=True)

    def repl_string(match: re.Match[str]) -> str:
        name = match.group(1)
        if name not in uk_map:
            raise KeyError(f"Missing Ukrainian translation for string: {name}")
        val = uk_map[name]
        if isinstance(val, dict):
            raise TypeError(f"Expected string for {name}, got plurals")
        return f'<string name="{name}">{escape_xml(val)}</string>'

    out = re.sub(r'<string name="([^"]+)">[^<]*</string>', repl_string, en_text)

    def repl_plurals_block(match: re.Match[str]) -> str:
        pname = match.group(1)
        body = match.group(2)
        if pname not in uk_map:
            raise KeyError(f"Missing Ukrainian translation for plurals: {pname}")
        val = uk_map[pname]
        if not isinstance(val, dict):
            raise TypeError(f"Expected plurals dict for {pname}")

        def item_repl(im: re.Match[str]) -> str:
            qty = im.group(1)
            if qty not in val:
                fallback = val.get("other") or val.get("many") or next(iter(val.values()))
                text = val.get(qty, fallback)
            else:
                text = val[qty]
            return f'<item quantity="{qty}">{escape_xml(text)}</item>'

        new_body = re.sub(r'<item quantity="([^"]+)">[^<]*</item>', item_repl, body)
        return f'<plurals name="{pname}">{new_body}</plurals>'

    out = re.sub(r'<plurals name="([^"]+)">(.*?)</plurals>', repl_plurals_block, out, flags=re.DOTALL)
    (ANDROID_UK / "strings.xml").write_text(out, encoding="utf-8")


def generate_android_xml_from_en(en_file: Path, uk_by_name: dict[str, str], out_file: Path) -> None:
    tree = ET.parse(en_file)
    root = tree.getroot()
    new_root = ET.Element("resources")
    if en_file.read_text(encoding="utf-8").startswith("<?xml"):
        pass  # match files without declaration
    for child in root:
        tag = child.tag.split("}")[-1]
        name = child.get("name")
        if tag == "string":
            elem = ET.SubElement(new_root, "string", name=name)
            if name not in uk_by_name:
                raise KeyError(f"Missing uk for {name} in {en_file.name}")
            elem.text = uk_by_name[name]
        else:
            new_root.append(copy.deepcopy(child))
    ANDROID_UK.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for child in new_root:
        tag = child.tag
        name = child.get("name")
        if tag == "string":
            text = child.text or ""
            escaped = (
                text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "\\'")
            )
            formatted = child.get("{http://schemas.android.com/apk/res/android}formatted")
            if formatted == "false":
                lines.append(f'    <string name="{name}" formatted="false">{escaped}</string>')
            else:
                lines.append(f'    <string name="{name}">{escaped}</string>')
    lines.append("</resources>")
    lines.append("")
    out_file.write_text("\n".join(lines), encoding="utf-8")


def get_en_value(key: str, entry: dict) -> str:
    locs = entry.get("localizations", {})
    if "en" in locs:
        en_loc = locs["en"]
        if "stringUnit" in en_loc:
            return en_loc["stringUnit"]["value"]
        if "variations" in en_loc:
            plural = en_loc["variations"].get("plural", {})
            for qty in ("other", "one", "few", "many"):
                if qty in plural:
                    return plural[qty]["stringUnit"]["value"]
    return key


def get_ru_value(entry: dict) -> str | None:
    locs = entry.get("localizations", {})
    ru = locs.get("ru")
    if not ru:
        return None
    if "stringUnit" in ru:
        return ru["stringUnit"]["value"]
    return None


def make_uk_unit(value: str) -> dict:
    return {"stringUnit": {"state": "translated", "value": value}}


IOS_KEY_UK: dict[str, str] = {
    "barcode.lookup.network_error": "Не вдалося знайти штрихкод. Перевірте з'єднання та спробуйте знову.",
    "barcode.lookup.rate_limited": "Занадто багато запитів штрихкода. Зачекайте трохи та спробуйте знову.",
    "barcode.product.fallback_name": "Штрихкод %@",
}


def resolve_uk(
    key: str,
    entry: dict,
    en_to_uk: dict[str, str],
    ru_to_uk: dict[str, str],
) -> str:
    if key in IOS_KEY_UK:
        return IOS_KEY_UK[key]
    en_val = get_en_value(key, entry)
    if en_val in en_to_uk:
        return en_to_uk[en_val]
    ru_val = get_ru_value(entry)
    if ru_val and ru_val in ru_to_uk:
        return ru_to_uk[ru_val]
    if key in en_to_uk:
        return en_to_uk[key]
    if ru_val:
        return convert_ru_to_uk(ru_val)
    if not en_val and not key:
        return ""
    raise KeyError(f"No uk translation for iOS key: {key!r} (en={en_val!r})")


def translate_variations(
    src_variations: dict | None,
    en_to_uk: dict[str, str],
    ru_to_uk: dict[str, str],
    ru_variations: dict | None,
) -> dict:
    if not src_variations:
        src_variations = ru_variations or {"plural": {}}
    result = copy.deepcopy(src_variations)
    plural = result.get("plural") or {}
    ru_plural = (ru_variations or {}).get("plural") or {}
    for qty, unit_entry in plural.items():
        en_val = unit_entry["stringUnit"]["value"]
        uk_val = en_to_uk.get(en_val)
        if not uk_val and qty in ru_plural:
            ru_val = ru_plural[qty]["stringUnit"]["value"]
            uk_val = ru_to_uk.get(ru_val)
        if not uk_val and qty in ru_plural:
            ru_val = ru_plural[qty]["stringUnit"]["value"]
            uk_val = ru_to_uk.get(ru_val) or convert_ru_to_uk(ru_val)
        if not uk_val:
            raise KeyError(f"No uk for plural {qty}: {en_val!r}")
        unit_entry["stringUnit"]["value"] = uk_val
    return result


def add_uk_localization(
    entry: dict,
    key: str,
    en_to_uk: dict[str, str],
    ru_to_uk: dict[str, str],
) -> None:
    if "localizations" not in entry:
        entry["localizations"] = {}
    locs = entry["localizations"]
    if "uk" in locs:
        return

    ru_loc = locs.get("ru")
    en_loc = locs.get("en")

    if ru_loc and "variations" in ru_loc:
        en_variations = (en_loc or {}).get("variations") or ru_loc.get("variations")
        uk_variations = translate_variations(
            en_variations,
            en_to_uk,
            ru_to_uk,
            ru_loc.get("variations"),
        )
        locs["uk"] = {"variations": uk_variations}
        return

    if en_loc and "variations" in en_loc:
        uk_variations = translate_variations(
            en_loc["variations"],
            en_to_uk,
            ru_to_uk,
            ru_loc.get("variations") if ru_loc else None,
        )
        locs["uk"] = {"variations": uk_variations}
        return

    uk_val = resolve_uk(key, entry, en_to_uk, ru_to_uk)
    locs["uk"] = make_uk_unit(uk_val)


def sort_localizations(locs: dict) -> dict:
    order = [
        "ar", "az", "de", "en", "es", "fr", "hi", "it", "ja", "ko", "nl",
        "pl", "pt-BR", "ro", "ru", "uk", "zh-Hans",
    ]
    sorted_locs = {}
    for lang in order:
        if lang in locs:
            sorted_locs[lang] = locs[lang]
    for lang in locs:
        if lang not in sorted_locs:
            sorted_locs[lang] = locs[lang]
    return sorted_locs


def update_xcstrings(path: Path, en_to_uk: dict[str, str], ru_to_uk: dict[str, str]) -> int:
    original = path.read_text(encoding="utf-8")
    data = json.loads(original)
    added = 0
    for key, entry in data.get("strings", {}).items():
        if not entry:
            entry = data["strings"][key] = {}
        before = copy.deepcopy(entry.get("localizations", {}))
        add_uk_localization(entry, key, en_to_uk, ru_to_uk)
        if entry.get("localizations") and "uk" not in before:
            added += 1
        if "localizations" in entry:
            entry["localizations"] = sort_localizations(entry["localizations"])

    compact = path.name == "BarcodeLookup.xcstrings"
    if compact:
        # Preserve compact-ish formatting: re-read and patch via regex after json roundtrip validation
        new_content = json.dumps(data, ensure_ascii=False, indent=2)
        new_content = re.sub(r'"localizations"\s*:\s*\{\s*\n\s*"en"', '"localizations" : { "en"', new_content)
        # Keep valid JSON; compact style is best-effort
        path.write_text(new_content + "\n", encoding="utf-8")
    else:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    json.loads(path.read_text(encoding="utf-8"))  # validate
    return added


def validate_android() -> None:
    uk_map = load_uk_map()
    en = parse_android_resources(ANDROID_EN / "strings.xml")
    uk = parse_android_resources(ANDROID_UK / "strings.xml")
    if set(en.keys()) != set(uk.keys()):
        missing = set(en.keys()) - set(uk.keys())
        extra = set(uk.keys()) - set(en.keys())
        raise SystemExit(f"Android key mismatch: missing={len(missing)} extra={len(extra)}")
    for name, en_val in en.items():
        uk_val = uk[name]
        if isinstance(en_val, str):
            for ph in re.findall(r"%[0-9]*\$?[sd@]|%[sd@]", en_val):
                if ph not in str(uk_val):
                    raise SystemExit(f"Placeholder mismatch for {name}: {ph}")
        elif isinstance(en_val, dict):
            if set(en_val.keys()) != set(uk_val.keys()):
                raise SystemExit(f"Plural quantity mismatch for {name}")


def main() -> None:
    if not CONTRIB_UK.exists():
        print("Contributor file missing at /tmp/uk_contrib.xml", file=sys.stderr)
        sys.exit(1)

    uk_map = load_uk_map()
    generate_android_strings_xml(uk_map)
    generate_android_xml_from_en(
        ANDROID_EN / "barcode_errors.xml", UK_BARCODE, ANDROID_UK / "barcode_errors.xml"
    )
    generate_android_xml_from_en(
        ANDROID_EN / "weekly_challenge.xml", UK_WEEKLY, ANDROID_UK / "weekly_challenge.xml"
    )

    en_to_uk = build_en_text_to_uk(uk_map)
    ru_to_uk = build_ru_text_to_uk(uk_map)

    ios_files = sorted(IOS_DIR.glob("*.xcstrings"))
    ios_counts = {}
    for path in ios_files:
        ios_counts[path.name] = update_xcstrings(path, en_to_uk, ru_to_uk)

    validate_android()

    en_count = len(parse_android_resources(ANDROID_EN / "strings.xml"))
    uk_count = len(parse_android_resources(ANDROID_UK / "strings.xml"))
    print(f"Android strings: EN={en_count} UK={uk_count}")
    print(f"iOS catalogs updated: {ios_counts}")


if __name__ == "__main__":
    main()

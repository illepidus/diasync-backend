# Diasync server

Серверная часть системы мониторинга глюкозы Diasync.

## Пример схемы контроля сахара крови ребёнка

Очевидно, схема неполная, на ней не хватает некоторых связей. Например, XDrip и ребенка и родителя может писать в
Nightscout. Но это сильно усложняет восприятие основных потоков данных, поэтому я оставил их за скобками.

```mermaid
flowchart LR
    pump([Инсулиновая помпа])
    sensor([Датчик сахара])

    subgraph g_cloud[Google cloud]
        firebase[Firebase]
    end

    subgraph phone_master[Телефон ребёнка]
        juglucco[Juglucco]
        xdrip_master[XDrip]
        aaps_master[AAPS]
        ds_master[Diasync]
    end
    subgraph server[Сервер]
        ds_srv[Diasync Server]
        nightscout[NightScout]
    end
    subgraph phone_slave[Телефон родителя]
        ds_slave[Diasync]
        aaps_slave[AAPS]
        xdrip_slave[XDrip]
    end

    sensor --> juglucco --> xdrip_master
    xdrip_master --> ds_master --> ds_srv --> ds_slave
    xdrip_master -.-> aaps_master <-.-> nightscout <-.-> aaps_slave
    xdrip_master <-.-> firebase <-.-> xdrip_slave
    aaps_master -.-> pump
    style ds_srv color: #FF8800
    click juglucco href "https://www.juggluco.nl/"
    click firebase href "https://firebase.google.com/"
    click xdrip_master,xdrip_slave href "https://github.com/NightscoutFoundation/xDrip"
    click aaps_master,aaps_slave href "https://github.com/nightscout/AndroidAPS"
    click ds_master,ds_slave href "https://github.com/illepidus/diasync-android"
    click nightscout href "https://github.com/nightscout/cgm-remote-monitor"
```

## Интеграция с API

Поддерживается REST API.

* [Swagger](https://diasync.krotarnya.ru/swagger)

## Kiosk интерфейс

В дополнение к основному приложению, реализован лёгкий web-фронтенд, доступный по пути
[/kiosk](https://diasync.krotarnya.ru/kiosk)

### Query параметры kiosk интерфейса

| Параметр | Описание                                                              | По умолчанию |
|----------|-----------------------------------------------------------------------|--------------|
| userId   | Идентификатор пользователя                                            | demo         |
| period   | Период отображения данных                                             | 1h           |
| low      | Нижний порог уровня глюкозы в мг/дл                                   | 70           |
| high     | Верхний порог уровня глюкозы в мг/дл                                  | 180          |
| stale    | Время в минутах, после которого текущее значение считается устаревшим | 10           |
| unit     | Единицы отображения: mmol или mgdl                                    | mmol         |

## Установка

Если есть необходимость развернуть отдельную инсталляцию diasync server, предлагаются следующие шаги:

1. Сделать fork этого репозитория
2. Изменить [.env](.github/.env) файл
3. Прописать секрет SSH_KEY в разделе [Settings > Secrets and variables > Actions](../../settings/secrets/actions)
   вашего репозитория
4. Запустить [Release workflow](../../actions/workflows/release.yml) в вашем репозитории.

Минимальные требования к серверу:

* 1 CPU / 1GB memory / 10GB storage
* Ubuntu 24.04
* Пользователь должен иметь root права

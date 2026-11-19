# Сторонние компоненты

Ковка запускает PRoot как отдельный процесс. Файлы PRoot и его загрузчика в `app/src/main/jniLibs` и библиотеки в `app/src/main/assets/runtime` извлечены из пакетов Termux для AArch64 и x86_64. Эти компоненты распространяются под собственными лицензиями.

| Компонент | Версия | Исходники и условия |
| --- | --- | --- |
| PRoot | 5.1.107.102 | [Исходники PRoot с лицензией GPL-2.0](https://github.com/termux/proot/tree/v5.1.107.102) |
| talloc | 2.5.0 | [Архив исходников и лицензий Samba](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz) |
| libandroid-shmem | 0.7 | [Исходники и лицензия BSD 3-Clause](https://github.com/termux/libandroid-shmem/tree/v0.7) |

Рецепты сборки пакетов, включая патчи, опубликованы в Termux Packages. [PRoot](https://github.com/termux/termux-packages/tree/850173a8d68e831dac6a8eb99fe9dd5718177139/packages/proot) · [talloc](https://github.com/termux/termux-packages/tree/850173a8d68e831dac6a8eb99fe9dd5718177139/packages/libtalloc) · [libandroid-shmem](https://github.com/termux/termux-packages/tree/850173a8d68e831dac6a8eb99fe9dd5718177139/packages/libandroid-shmem)

Linux-среда и инструменты загружаются по запросу. Их источники и контрольные суммы указаны в [каталоге инструментов](app/src/main/assets/toolchains.tsv) и [коде подготовки среды](app/src/main/java/dev/kovka/app/EmbeddedRuntime.kt).

# Код: стандарт репо

Источник истины по оформлению кода. Правила ниже применяются при проектировании шва и при
ревью, а не только при написании нового кода.

## Порядок параметров

Колбек- и лямбда-параметры идут **последними** — в методе, функции и конструкторе.

Список читается сверху вниз как «данные и зависимости, затем канал обратной связи». Колбек в
середине списка разрывает эту последовательность и заставляет читателя искать, к чему относятся
параметры после него.

Правильно:

```kotlin
class DownloadQueue(
    private val parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val workContext: CoroutineContext,
    private val config: StateFlow<AppConfig>,
)

suspend fun download(
    mediaId: String,
    quality: Quality,
    targetPath: Path,
    onProgress: (SourceProgress) -> Unit,
): DownloadResult
```

Конструктор — шов сборки, а не шов обратного вызова: зависимостями идут скоуп, диспетчер
конфайнмента, рабочий контекст и конфигурация. Дефолтных значений у них нет — способ построения
зависимости знает только композиционный код; исключение — часы (`clock`, по умолчанию системные).

Неправильно:

```kotlin
// колбек зажат между зависимостями и дефолтом
class DownloadQueue(
    private val parentScope: CoroutineScope,
    private val config: StateFlow<AppConfig>,
    private val onProgress: (TaskId, Progress) -> Unit,
    private val clock: Clock = Clock.System,
)
```

**У функции с дефолтными параметрами и колбеком дефолты не должны оказаться после колбека.**
Вызов, опускающий дефолты, читался бы так, будто колбек — тоже дефолт; кроме того ломается
trailing lambda. Сначала идут параметры с дефолтами, затем колбек.

Причина правила в Kotlin: trailing lambda работает только для последнего параметра, а вынос
колбека в конец — единственный способ и сохранить его, и не разрывать список зависимостей.

## Где применяется

Швы ядра: контракт `SourceAdapter` (`core/.../SourceAdapter.kt`), порты `MediaTool`
(`core/.../MediaTool.kt`) и `HttpTool` (`core/.../HttpTool.kt`), `DownloadQueue`
(`core/.../DownloadQueue.kt`). Новый шов с
колбеком проектируется по этому правилу; отклонение фиксируется в спеке фичи или в ADR.

## Sealed-типы исходов — только возврат

Sealed-типы исходов (`ResolveResult`, `LoadMetaResult`, `DownloadResult`) — только типы
возврата метода. В качестве аргумента передаётся извлечённое значение (VO), не элемент
sealed-результата. Результат описывает исход операции, а не вход в следующую.

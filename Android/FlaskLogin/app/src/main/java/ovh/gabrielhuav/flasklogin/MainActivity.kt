package ovh.gabrielhuav.flasklogin

import android.os.Bundle
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import ovh.gabrielhuav.flasklogin.ui.theme.FlaskLoginTheme
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// =========================================================
// ACTIVITY PRINCIPAL
// =========================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlaskLoginTheme {
                AppNavigation()
            }
        }
    }
}

// =========================================================
// ESTADO DE UI Y SESIÓN
// =========================================================
sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

object SessionManager {
    var isLoggedIn by mutableStateOf(false)
        private set
    var username by mutableStateOf<String?>(null)
        private set
    var token by mutableStateOf<String?>(null)
        private set
    var role by mutableStateOf<String?>(null)
        private set
    val isAdmin: Boolean
        get() = role == "admin"

    fun login(user: String, jwt: String, userRole: String) {
        isLoggedIn = true
        username = user
        token = jwt
        role = userRole
    }

    fun logout() {
        isLoggedIn = false
        username = null
        token = null
        role = null
    }

    // Header listo para usar en cada llamada protegida
    fun authHeader(): String = "Bearer ${token ?: ""}"
}

// =========================================================
// MODELOS DE DATOS
// =========================================================
data class LoginRequest(val username: String, val password: String)
data class LoginResponse(
    val status: String,
    val message: String,
    val user_id: Int? = null,
    val username: String? = null,
    val role: String? = null,
    val token: String? = null
)
data class RegisterRequest(val username: String, val password: String)
data class RegisterResponse(val message: String)

data class Tarea(
    val id: Int? = null,
    val titulo: String,
    val descripcion: String = "",
    val entregada: Boolean = false,
    val documento_nombre: String? = null,
    val calificacion: Double? = null,
    val comentario_admin: String? = null
)
data class EntregaRequest(
    val documento_nombre: String,
    val documento_base64: String
)

data class CalificarRequest(
    val calificacion: Double,
    val comentario_admin: String
)
data class DocumentoResponse(
    val documento_nombre: String?,
    val documento_base64: String?
)
data class Entrega(
    val id: Int,
    val task_id: Int,
    val user_id: Int,
    val username: String? = null,
    val documento_nombre: String? = null,
    val calificacion: Double? = null,
    val comentario_admin: String? = null
)

// =========================================================
// API + RETROFIT
// =========================================================
interface ApiService {
    @POST("/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @GET("/tasks")
    suspend fun getTasks(@Header("Authorization") token: String): List<Tarea>

    @POST("/tasks")
    suspend fun createTask(@Header("Authorization") token: String, @Body tarea: Tarea): Tarea

    @PUT("/tasks/{id}")
    suspend fun updateTask(
        @Header("Authorization") token: String,
        @Path("id") id: Int,
        @Body tarea: Tarea
    ): Tarea

    @DELETE("/tasks/{id}")
    suspend fun deleteTask(@Header("Authorization") token: String, @Path("id") id: Int)

    @PUT("/tasks/{id}/entregar")
    suspend fun entregarTarea(
        @Header("Authorization") token: String,
        @Path("id") id: Int,
        @Body entrega: EntregaRequest
    ): Tarea

    // NUEVO: el admin pide todas las entregas (una por alumno) de una tarea
    @GET("/tasks/{id}/entregas")
    suspend fun getEntregas(
        @Header("Authorization") token: String,
        @Path("id") taskId: Int
    ): List<Entrega>

    // CAMBIÓ: ahora se pide el documento por el ID de la ENTREGA, no de la tarea
    @GET("/entregas/{id}/documento")
    suspend fun getDocumento(
        @Header("Authorization") token: String,
        @Path("id") entregaId: Int
    ): DocumentoResponse

    // CAMBIÓ: ahora se califica por el ID de la ENTREGA, no de la tarea
    @PUT("/entregas/{id}/calificar")
    suspend fun calificarEntrega(
        @Header("Authorization") token: String,
        @Path("id") entregaId: Int,
        @Body calificacion: CalificarRequest
    ): Entrega
}

object RetrofitInstance {
    // 10.0.2.2 = localhost de tu PC visto desde el emulador Android
    private const val BASE_URL = "http://10.0.2.2:5000"

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

// =========================================================
// VIEWMODELS
// =========================================================
class LoginViewModel : ViewModel() {
    var uiState by mutableStateOf<UiState<Unit>>(UiState.Idle)
        private set

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            uiState = UiState.Error("Completa usuario y contraseña")
            return
        }
        viewModelScope.launch {
            uiState = UiState.Loading
            try {
                val response = RetrofitInstance.api.login(LoginRequest(username, password))
                if (response.status == "success" && response.token != null) {
                    SessionManager.login(response.username ?: username, response.token, response.role ?: "normal")
                    uiState = UiState.Success(Unit)
                } else {
                    uiState = UiState.Error(response.message)
                }
            } catch (e: Exception) {
                uiState = UiState.Error("Error de conexión: ${e.message}")
            }
        }
    }
}

class RegisterViewModel : ViewModel() {
    var uiState by mutableStateOf<UiState<String>>(UiState.Idle)
        private set

    fun register(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            uiState = UiState.Error("Completa usuario y contraseña")
            return
        }
        viewModelScope.launch {
            uiState = UiState.Loading
            try {
                val response = RetrofitInstance.api.register(RegisterRequest(username, password))
                uiState = UiState.Success(response.message)
            } catch (e: Exception) {
                uiState = UiState.Error("Error de conexión: ${e.message}")
            }
        }
    }
}

class CrudViewModel : ViewModel() {
    var uiState by mutableStateOf<UiState<List<Tarea>>>(UiState.Idle)
        private set

    var operacionEnCurso by mutableStateOf(false)
        private set

    var operacionError by mutableStateOf<String?>(null)
        private set

    var documentoActual by mutableStateOf<DocumentoResponse?>(null)
        private set
    var documentoError by mutableStateOf<String?>(null)
        private set

    // NUEVO: lista de entregas (alumnos) de la tarea que se esté revisando
    var entregasState by mutableStateOf<UiState<List<Entrega>>>(UiState.Idle)
        private set

    fun loadTasks() {
        viewModelScope.launch {
            uiState = UiState.Loading
            try {
                val tareas = RetrofitInstance.api.getTasks(SessionManager.authHeader())
                uiState = UiState.Success(tareas)
            } catch (e: Exception) {
                uiState = UiState.Error("Error al cargar: ${e.message}")
            }
        }
    }

    fun crearTarea(titulo: String, descripcion: String) {
        if (titulo.isBlank()) {
            operacionError = "El título no puede estar vacío"
            return
        }
        viewModelScope.launch {
            operacionEnCurso = true
            operacionError = null
            try {
                RetrofitInstance.api.createTask(
                    SessionManager.authHeader(),
                    Tarea(titulo = titulo, descripcion = descripcion)
                )
                loadTasks()
            } catch (e: Exception) {
                operacionError = "Error al crear: ${e.message}"
            } finally {
                operacionEnCurso = false
            }
        }
    }

    fun actualizarTarea(id: Int, titulo: String, descripcion: String) {
        if (titulo.isBlank()) {
            operacionError = "El título no puede estar vacío"
            return
        }
        viewModelScope.launch {
            operacionEnCurso = true
            operacionError = null
            try {
                RetrofitInstance.api.updateTask(
                    SessionManager.authHeader(),
                    id,
                    Tarea(titulo = titulo, descripcion = descripcion)
                )
                loadTasks()
            } catch (e: Exception) {
                operacionError = "Error al actualizar: ${e.message}"
            } finally {
                operacionEnCurso = false
            }
        }
    }

    fun eliminarTarea(id: Int) {
        viewModelScope.launch {
            operacionEnCurso = true
            operacionError = null
            try {
                RetrofitInstance.api.deleteTask(SessionManager.authHeader(), id)
                loadTasks()
            } catch (e: Exception) {
                operacionError = "Error al eliminar: ${e.message}"
            } finally {
                operacionEnCurso = false
            }
        }
    }

    // NUEVO: cargar la lista de entregas (alumnos) de una tarea específica
    fun loadEntregas(taskId: Int) {
        viewModelScope.launch {
            entregasState = UiState.Loading
            try {
                val entregas = RetrofitInstance.api.getEntregas(SessionManager.authHeader(), taskId)
                entregasState = UiState.Success(entregas)
            } catch (e: Exception) {
                entregasState = UiState.Error("Error al cargar entregas: ${e.message}")
            }
        }
    }

    // CAMBIÓ: ahora recibe el ID de la ENTREGA, no de la tarea
    fun calificarEntrega(entregaId: Int, calificacion: Double, comentario: String, taskId: Int) {
        viewModelScope.launch {
            operacionEnCurso = true
            operacionError = null
            try {
                RetrofitInstance.api.calificarEntrega(
                    SessionManager.authHeader(),
                    entregaId,
                    CalificarRequest(calificacion, comentario)
                )
                loadEntregas(taskId)
            } catch (e: Exception) {
                operacionError = "Error al calificar: ${e.message}"
            } finally {
                operacionEnCurso = false
            }
        }
    }

    // CAMBIÓ: ahora recibe el ID de la ENTREGA, no de la tarea
    fun verDocumento(entregaId: Int) {
        viewModelScope.launch {
            documentoError = null
            try {
                documentoActual = RetrofitInstance.api.getDocumento(SessionManager.authHeader(), entregaId)
            } catch (e: Exception) {
                documentoError = "Error al abrir documento: ${e.message}"
            }
        }
    }

    fun cerrarDocumento() {
        documentoActual = null
    }
}
class TareaNormalViewModel : ViewModel() {
    var uiState by mutableStateOf<UiState<List<Tarea>>>(UiState.Idle)
        private set
    var operacionEnCurso by mutableStateOf(false)
        private set
    var operacionError by mutableStateOf<String?>(null)
        private set

    fun loadTasks() {
        viewModelScope.launch {
            uiState = UiState.Loading
            try {
                val tareas = RetrofitInstance.api.getTasks(SessionManager.authHeader())
                uiState = UiState.Success(tareas)
            } catch (e: Exception) {
                uiState = UiState.Error("Error al cargar: ${e.message}")
            }
        }
    }

    fun entregarTarea(id: Int, nombreArchivo: String, base64: String) {
        viewModelScope.launch {
            operacionEnCurso = true
            operacionError = null
            try {
                RetrofitInstance.api.entregarTarea(
                    SessionManager.authHeader(),
                    id,
                    EntregaRequest(nombreArchivo, base64)
                )
                loadTasks()
            } catch (e: Exception) {
                operacionError = "Error al entregar: ${e.message}"
            } finally {
                operacionEnCurso = false
            }
        }
    }
}



// =========================================================
// PANTALLAS: LOGIN Y REGISTRO
// =========================================================
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(),
    onLoginSuccess: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(viewModel.uiState) {
        if (viewModel.uiState is UiState.Success) onLoginSuccess()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Iniciar Sesión", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        when (val state = viewModel.uiState) {
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            else -> {}
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.login(username, password) },
            enabled = viewModel.uiState !is UiState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Entrar")
        }
    }
}

@Composable
fun RegisterScreen(
    viewModel: RegisterViewModel = viewModel(),
    onRegisterSuccess: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(viewModel.uiState) {
        if (viewModel.uiState is UiState.Success) onRegisterSuccess()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Registro de Usuario", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        when (val state = viewModel.uiState) {
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is UiState.Success -> Text(state.data, color = MaterialTheme.colorScheme.primary)
            else -> {}
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.register(username, password) },
            enabled = viewModel.uiState !is UiState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Registrarme")
        }
    }
}

// =========================================================
// PANTALLA CRUD COMPLETA (crear, listar, editar, eliminar)
// =========================================================
@Composable
fun CrudListScreen(viewModel: CrudViewModel = viewModel()) {
    LaunchedEffect(Unit) { viewModel.loadTasks() }

    var mostrarFormulario by remember { mutableStateOf(false) }
    var tareaEnEdicion by remember { mutableStateOf<Tarea?>(null) }
    var tareaViendoEntregas by remember { mutableStateOf<Tarea?>(null) }

    // Si el admin eligió "Ver entregas" de una tarea, mostramos esa pantalla en vez de la lista
    if (tareaViendoEntregas != null) {
        EntregasScreen(
            tarea = tareaViendoEntregas!!,
            viewModel = viewModel,
            onBack = { tareaViendoEntregas = null }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tareas (Admin)", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = {
                    tareaEnEdicion = null
                    mostrarFormulario = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Agregar tarea")
                }
            }
            Spacer(Modifier.height(8.dp))

            viewModel.operacionError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            when (val state = viewModel.uiState) {
                is UiState.Loading -> CircularProgressIndicator()
                is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is UiState.Success -> {
                    LazyColumn {
                        items(state.data) { tarea ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(tarea.titulo, style = MaterialTheme.typography.titleMedium)
                                            if (tarea.descripcion.isNotBlank()) {
                                                Text(tarea.descripcion, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        Row {
                                            IconButton(onClick = {
                                                tareaEnEdicion = tarea
                                                mostrarFormulario = true
                                            }) {
                                                Icon(Icons.Default.Edit, contentDescription = "Editar")
                                            }
                                            IconButton(onClick = {
                                                tarea.id?.let { viewModel.eliminarTarea(it) }
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { tareaViendoEntregas = tarea }) {
                                        Text("Ver entregas")
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        if (mostrarFormulario) {
            TareaFormDialog(
                tareaExistente = tareaEnEdicion,
                enCurso = viewModel.operacionEnCurso,
                onDismiss = { mostrarFormulario = false },
                onGuardar = { titulo, descripcion ->
                    if (tareaEnEdicion == null) {
                        viewModel.crearTarea(titulo, descripcion)
                    } else {
                        viewModel.actualizarTarea(tareaEnEdicion!!.id!!, titulo, descripcion)
                    }
                    mostrarFormulario = false
                }
            )
        }
    }
}
@Composable
fun EntregasScreen(
    tarea: Tarea,
    viewModel: CrudViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(tarea.id) { tarea.id?.let { viewModel.loadEntregas(it) } }

    var entregaACalificar by remember { mutableStateOf<Entrega?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                }
                Text(tarea.titulo, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(8.dp))

            viewModel.operacionError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            when (val state = viewModel.entregasState) {
                is UiState.Loading -> CircularProgressIndicator()
                is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        Text("Nadie ha entregado esta tarea todavía.")
                    } else {
                        LazyColumn {
                            items(state.data) { entrega ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(entrega.username ?: "Alumno", style = MaterialTheme.typography.titleMedium)
                                        Text("📎 ${entrega.documento_nombre}")
                                        if (entrega.calificacion != null) {
                                            Text("Calificación: ${entrega.calificacion}")
                                            if (!entrega.comentario_admin.isNullOrBlank()) {
                                                Text("Comentario: ${entrega.comentario_admin}")
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row {
                                            Button(onClick = { viewModel.verDocumento(entrega.id) }) {
                                                Text("Ver documento")
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Button(onClick = { entregaACalificar = entrega }) {
                                                Text(if (entrega.calificacion != null) "Cambiar nota" else "Calificar")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        entregaACalificar?.let { entrega ->
            CalificarDialog(
                entrega = entrega,
                enCurso = viewModel.operacionEnCurso,
                onDismiss = { entregaACalificar = null },
                onCalificar = { calificacion, comentario ->
                    tarea.id?.let { viewModel.calificarEntrega(entrega.id, calificacion, comentario, it) }
                    entregaACalificar = null
                }
            )
        }

        viewModel.documentoActual?.let { doc ->
            DocumentoDialog(doc = doc, onDismiss = { viewModel.cerrarDocumento() })
        }
    }
}

@Composable
fun CalificarDialog(
    entrega: Entrega,
    enCurso: Boolean,
    onDismiss: () -> Unit,
    onCalificar: (Double, String) -> Unit
) {
    var calificacion by remember { mutableStateOf(entrega.calificacion?.toString() ?: "") }
    var comentario by remember { mutableStateOf(entrega.comentario_admin ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calificar a ${entrega.username ?: "alumno"}") },
        text = {
            Column {
                Text("Documento entregado: ${entrega.documento_nombre}")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = calificacion,
                    onValueChange = { calificacion = it },
                    label = { Text("Calificación (0-10)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = comentario,
                    onValueChange = { comentario = it },
                    label = { Text("Comentario") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cal = calificacion.toDoubleOrNull()
                    if (cal != null) onCalificar(cal, comentario)
                },
                enabled = !enCurso
            ) {
                Text(if (enCurso) "Guardando..." else "Guardar calificación")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
@Composable
fun DocumentoDialog(doc: DocumentoResponse, onDismiss: () -> Unit) {
    val bitmap = remember(doc.documento_base64) {
        try {
            val bytes = android.util.Base64.decode(doc.documento_base64, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(doc.documento_nombre ?: "Documento") },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Documento entregado",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("Este archivo no se puede previsualizar como imagen (puede ser un PDF u otro formato). Nombre: ${doc.documento_nombre}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

@Composable
fun TareaFormDialog(
    tareaExistente: Tarea?,
    enCurso: Boolean,
    onDismiss: () -> Unit,
    onGuardar: (titulo: String, descripcion: String) -> Unit
) {
    var titulo by remember { mutableStateOf(tareaExistente?.titulo ?: "") }
    var descripcion by remember { mutableStateOf(tareaExistente?.descripcion ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tareaExistente == null) "Nueva Tarea" else "Editar Tarea") },
        text = {
            Column {
                OutlinedTextField(
                    value = titulo,
                    onValueChange = { titulo = it },
                    label = { Text("Título") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = descripcion,
                    onValueChange = { descripcion = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGuardar(titulo, descripcion) },
                enabled = !enCurso
            ) {
                Text(if (enCurso) "Guardando..." else "Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// =========================================================
// NAVEGACIÓN Y MENÚ (DRAWER)
// =========================================================
sealed class Screen(val route: String, val label: String) {
    object Login : Screen("login", "Inicio de Sesión")
    object Register : Screen("register", "Registro de Usuario")
    object Home : Screen("home", "Inicio")
    object Crud : Screen("crud", "Operaciones CRUD")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController: NavHostController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text("Menú", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()

                NavigationDrawerItem(
                    label = { Text(Screen.Login.label) },
                    selected = false,
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(Screen.Login.route)
                    }
                )
                NavigationDrawerItem(
                    label = { Text(Screen.Register.label) },
                    selected = false,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(Screen.Register.route)
                    }
                )
                NavigationDrawerItem(
                    label = { Text(Screen.Crud.label) },
                    selected = false,
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    modifier = Modifier.alpha(if (SessionManager.isAdmin) 1f else 0.4f),
                    onClick = {
                        if (SessionManager.isAdmin) {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.Crud.route)
                        }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (SessionManager.isLoggedIn) "Hola, ${SessionManager.username}"
                            else "FlaskLogin"
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menú")
                        }
                    },
                    actions = {
                        if (SessionManager.isLoggedIn) {
                            IconButton(onClick = {
                                SessionManager.logout()
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }) {
                                Icon(Icons.Default.ExitToApp, contentDescription = "Cerrar sesión")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Login.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Screen.Login.route) {
                    LoginScreen(onLoginSuccess = {
                        val destino = if (SessionManager.isAdmin) Screen.Crud.route else Screen.Home.route
                        navController.navigate(destino) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    })
                }
                composable(Screen.Register.route) {
                    RegisterScreen(onRegisterSuccess = {
                        navController.navigate(Screen.Login.route)
                    })
                }
                composable(Screen.Home.route) {
                    TareaNormalListScreen()
                }
                composable(Screen.Crud.route) {
                    CrudListScreen()
                }
            }
        }
    }
}
@Composable
fun TareaNormalListScreen(viewModel: TareaNormalViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadTasks() }

    var tareaSeleccionada by remember { mutableStateOf<Tarea?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && tareaSeleccionada != null) {
            val nombreArchivo = obtenerNombreArchivo(context, uri) ?: "documento"
            val base64 = uriToBase64(context, uri)
            if (base64 != null) {
                viewModel.entregarTarea(tareaSeleccionada!!.id!!, nombreArchivo, base64)
            }
            tareaSeleccionada = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Mis Tareas", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        viewModel.operacionError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        when (val state = viewModel.uiState) {
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    Text("No hay tareas asignadas todavía.")
                } else {
                    LazyColumn {
                        items(state.data) { tarea ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(tarea.titulo, style = MaterialTheme.typography.titleMedium)
                                    if (tarea.descripcion.isNotBlank()) {
                                        Text(tarea.descripcion, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Spacer(Modifier.height(4.dp))

                                    if (tarea.entregada) {
                                        Text("✔ Entregada: ${tarea.documento_nombre}", color = MaterialTheme.colorScheme.primary)
                                        if (tarea.calificacion != null) {
                                            Text("Calificación: ${tarea.calificacion}")
                                            if (!tarea.comentario_admin.isNullOrBlank()) {
                                                Text("Comentario: ${tarea.comentario_admin}")
                                            }
                                        } else {
                                            Text("Pendiente de calificación", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        Button(onClick = {
                                            tareaSeleccionada = tarea
                                            filePickerLauncher.launch("*/*")
                                        }, enabled = !viewModel.operacionEnCurso) {
                                            Text(if (viewModel.operacionEnCurso) "Subiendo..." else "Adjuntar y Entregar")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {}
        }
    }
}

// Funciones auxiliares para leer el archivo elegido
fun obtenerNombreArchivo(context: android.content.Context, uri: Uri): String? {
    var nombre: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && idx >= 0) nombre = cursor.getString(idx)
    }
    return nombre
}

fun uriToBase64(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        null
    }
}
# Usar una imagen base ligera de Python
FROM python:3.9-slim

# Establecer el directorio de trabajo dentro del contenedor
WORKDIR /app

# Copiar los requerimientos primero (para aprovechar la caché de Docker)
COPY requirements.txt .

# Instalar dependencias
RUN pip install --no-cache-dir -r requirements.txt

# Copiar el resto del código
COPY . .

# Exponer el puerto 5000
EXPOSE 5000

# Comando para ejecutar la app
CMD ["python", "app.py"]
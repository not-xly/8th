# Reglas R8 para 8th.
# audio_service, just_audio y androidx.car.app traen sus propias reglas
# consumer; estas son solo las redes de seguridad locales.

# Código nativo propio: se accede por MethodChannel con nombres en strings,
# R8 no lo elimina (hay referencias directas), pero se conserva explícito
# para que cualquier renombrado futuro nunca rompa los canales.
-keep class com.xly.u_8th.** { *; }

# Car App Library: el host de plantillas instancia pantallas/sesiones vía
# reflection del framework CarAppService.
-keep class androidx.car.app.** { *; }

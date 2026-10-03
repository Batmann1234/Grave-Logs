@echo off
echo Compilando GraveLogs (necesitas JDK 21 y Maven instalados)...
call mvn clean package
echo.
echo Listo. El .jar esta en la carpeta "target" (GraveLogs.jar). Copialo a la carpeta plugins de tu servidor.
pause

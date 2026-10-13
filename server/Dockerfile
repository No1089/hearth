FROM python:3.13-slim
# What the Supervisor wants on an image it didn't build with its own builder
# (it passes BUILD_VERSION from config.yaml when it builds this itself).
ARG BUILD_VERSION
LABEL io.hass.version="${BUILD_VERSION}" io.hass.type="app" io.hass.arch="aarch64|amd64"
WORKDIR /app
RUN pip install --no-cache-dir flask websockets
COPY app.py ./
COPY static ./static
ENV PORT=5007
EXPOSE 5007
VOLUME /app/data
CMD ["python", "app.py"]

FROM python:3.13-slim
WORKDIR /app
RUN pip install --no-cache-dir flask websockets
COPY app.py ./
COPY static ./static
ENV PORT=5007
EXPOSE 5007
VOLUME /app/data
CMD ["python", "app.py"]

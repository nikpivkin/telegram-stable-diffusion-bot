import io
import json
import os
import sys
from datetime import timedelta
from typing import TypedDict, List

import pika
import torch
from PIL import Image
from diffusers import StableDiffusionPipeline, DPMSolverMultistepScheduler
from dotenv import load_dotenv
from minio import Minio
from pika.adapters.blocking_connection import BlockingChannel

load_dotenv()


def ger_env_or_throw(key):
    val = os.getenv(key)
    if not val:
        raise Exception(f"{key} not found")
    return val


BUCKET_NAME = ger_env_or_throw("AWS_BUCKET")
AMQP_URL = ger_env_or_throw("AMQP_URL")

DEFAULT_EXCHANGE_NAME = "tgsd"
TEXT_2_IMG_QUEUE = "txt2img"
IMG_QUEUE = "img"

if not torch.cuda.is_available():
    raise Exception("This device does not supported")

# TODO
MODEL_NAME = "stabilityai/stable-diffusion-2-base"

minio_client = Minio(
    os.getenv("AWS_ENDPOINT", "127.0.0.1:9000"),
    access_key=os.getenv("AWS_ACCESS_KEY", "minioadmin"),
    secret_key=os.getenv("AWS_SECRET_KEY", "minioadmin"),
    region=os.getenv("AWS_REGION", "eu-north-1"),
    secure=True
)

found = minio_client.bucket_exists(BUCKET_NAME)
if not found:
    minio_client.make_bucket(BUCKET_NAME)
else:
    print(f"Bucket {BUCKET_NAME} already exists")

pipeline = StableDiffusionPipeline.from_pretrained(MODEL_NAME, torch_dtype=torch.float16, revision="fp16")
pipeline.scheduler = DPMSolverMultistepScheduler.from_config(pipeline.scheduler.config)
pipeline.to("cuda")
print("pipline created")

Txt2ImgEvent = TypedDict(
    "Txt2ImgEvent", {
        "prompt": str,
        "chatId": int,
        "messageId": int
    }
)

ImgGeneratedEvent = TypedDict(
    "ImgGeneratedEvent", {
        "key": str,
        "chatId": int,
        "messageId": int
    }
)


def save_img(name: str, img: io.BytesIO):
    minio_client.put_object(
        bucket_name=BUCKET_NAME,
        object_name=name,
        data=img, length=img.getbuffer().nbytes
    )


def get_presigned_url(name: str) -> str:
    return minio_client.presigned_get_object(
        BUCKET_NAME, object_name=name, expires=timedelta(hours=1)
    )


def save_img_and_get_url(name: str, img: io.BytesIO) -> str:
    save_img(name, img)
    return get_presigned_url(name)


def generate_img(prompt: str) -> io.BytesIO:
    images: List[Image.Image] = pipeline(prompt, num_inference_steps=25).images
    return img_to_readable(images[0])


def img_to_readable(img: Image.Image) -> io.BytesIO:
    byte_io = io.BytesIO()
    img.save(byte_io, format="PNG")
    # TODO use getvalue?
    byte_io.seek(0)
    return byte_io


def txt2img_callback(
        ch: BlockingChannel,
        method: pika.spec.Basic.Deliver,
        properties: pika.spec.BasicProperties,
        body: bytes
):
    msg: Txt2ImgEvent = json.loads(body.decode())
    prompt = msg['prompt']

    # Start generate img
    img = generate_img(prompt)
    img_name = f"/txt2img/{hash(prompt)}.png"

    img_url = save_img_and_get_url(img_name, img)

    ch.basic_ack(method.delivery_tag)

    event: ImgGeneratedEvent = {
        "key": img_url,
        "chatId": msg["chatId"],
        "messageId": msg["messageId"]
    }

    ch.basic_publish(
        exchange=DEFAULT_EXCHANGE_NAME,
        routing_key=IMG_QUEUE,
        body=json.dumps(event).encode(),
        properties=pika.BasicProperties(delivery_mode=2)
    )


def main():
    params = pika.URLParameters(AMQP_URL)

    with pika.BlockingConnection(params) as conn:
        with conn.channel() as channel:
            channel.basic_consume(TEXT_2_IMG_QUEUE, txt2img_callback)
            channel.start_consuming()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print('Interrupted')
        try:
            sys.exit(0)
        except SystemExit:
            os._exit(0)

import { SkipServiceBroker } from "@skipruntime/helpers";

import express from "express";
import { Kafka } from "kafkajs";

const service = new SkipServiceBroker({
  host: "reactive_cache",
  control_port: 8081,
  streaming_port: 8080,
});

const app = express();

app.use(express.json());

const kafka = new Kafka({
  brokers: ["kafka:19092"],
  clientId: "web-backend",
});

function encode(
  msg: { author: string; body: string } | { message_id: number },
  timestamp: number = Date.now(),
): { value: string } {
  // generate numeric IDs by concatenating the current time with 4 digits of noise
  // so that IDs are ordered and vanishingly unlikely to collide
  const id = Math.floor(10_000 * (timestamp + Math.random()));
  return {
    value: JSON.stringify({
      ...msg,
      id,
      timestamp,
    }),
  };
}

const initial_messages: { value: string }[] = [
  encode({ author: "Bob", body: "Hey guys!" }, Date.now() - 30_000),
  encode({ author: "Alice", body: "Hi, Bob" }, Date.now() - 20_000),
  encode(
    { author: "Eve", body: "Welcome to the chatroom" },
    Date.now() - 10_000,
  ),
  encode({
    author: "Skip",
    body: "Try sending messages/likes and see them reflect instantly across multiple tabs! All data is written to Kafka, then reactively processed and pushed to the client by Skip",
  }),
];
const initial_likes: { value: string }[] = [
  encode({
    message_id: JSON.parse((initial_messages[0] as { value: string }).value).id,
  }),
  encode({
    message_id: JSON.parse((initial_messages[0] as { value: string }).value).id,
  }),
  encode({
    message_id: JSON.parse((initial_messages[3] as { value: string }).value).id,
  }),
];

const producer = { ...kafka.producer(), isConnected: false };
producer.on("producer.connect", () => {
  producer.isConnected = true;
  producer
    .send({ topic: "skip-chatroom-messages", messages: initial_messages })
    .then(
      () =>
        console.log(
          "successfully populated initial likes: " + initial_messages,
        ),
      (e) => {
        console.error("Error populating initial Kafka messages");
        throw e;
      },
    );
  producer.send({ topic: "skip-chatroom-likes", messages: initial_likes }).then(
    () => console.log("successfully populated initial likes: " + initial_likes),
    (e) => {
      console.error("Error populating initial Kafka likes");
      throw e;
    },
  );
});
producer.on("producer.disconnect", () => {
  producer.isConnected = false;
  //attempt to reconnect
  producer.connect();
});

producer.connect();

app.get("/messages", (_req, res) => {
  service
    .getStreamUUID("messages")
    .then((uuid) => {
      res.redirect(307, `/streams/${uuid}`);
    })
    .catch((e: unknown) => res.status(500).json(e));
});

app.put("/message", (req, res) => {
  const msg = encode(req.body);
  producer.send({ topic: "skip-chatroom-messages", messages: [msg] }).then(
    () => {
      res.status(200).json({});
    },
    (e) => {
      console.error("kafka producer error: ", e);
      throw e;
    },
  );
});
app.put("/like/:message_id", (req, res) => {
  const like = encode({ message_id: Number(req.params.message_id) });
  producer.send({ topic: "skip-chatroom-likes", messages: [like] }).then(
    () => res.status(200).json({}),
    (e) => {
      console.error("kafka producer error: ", e);
      throw e;
    },
  );
});

app.get("/healthcheck", (_req, res) => {
  if (producer.isConnected) res.status(200).json({});
  else res.status(503).json({ health_status: "Disconnected from Kafka" });
});

const port = 3031;
app.listen(port, () => {
  console.log(`Web backend listening at port ${port.toString()}`);
});

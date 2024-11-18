import { RESTWrapperOfSkipService } from "@skipruntime/helpers/rest.js";

import express from "express";

/*
  This is the user facing server of the database example
*/

const service = new RESTWrapperOfSkipService({
  host: "localhost",
  port: 8081,
});

import sqlite3 from "sqlite3";

type User = {
  name: string;
  country: string;
};

const db = new sqlite3.Database("./db.sqlite");
const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
// WRITES

const run = function (
  query: string,
  params: { [param: string]: string },
): Promise<void> {
  return new Promise((resolve, reject) => {
    db.run(query, params, (err) => {
      if (err) reject(err);
      else resolve();
    });
  });
};

app.get("/user/:id", (_req, res) => {
  service
    .getArray("users", {}, "123")
    .then((user) => {
      res.status(200).json(user);
    })
    .catch(() => {
      res.status(500).json("Internal error");
    });
});

app.put("/user/:id", (req, res) => {
  const key = req.params.id;
  const data = req.body as User;
  run("INSERT OR REPLACE INTO data (id, object) VALUES ($id, $object)", {
    $id: key,
    $object: JSON.stringify(data),
  })
    .then(() => service.put("users", key, [data]))
    .then(() => {
      res.status(200).json({});
    })
    .catch((e: unknown) => {
      console.log(e);
      res.status(500).json("Internal error");
    });
});
app.delete("/user/:id", (req, res) => {
  const key = req.params.id;
  run("DELETE FROM data WHERE id = $id", { $id: key })
    .then(() => service.deleteKey("users", key))
    .then(() => {
      res.status(200).json({});
    })
    .catch(() => {
      res.status(500).json("Internal error");
    });
});

const port = 8082;

app.listen(port, () => {
  console.log(`Example app listening on port ${port.toString()}`);
});

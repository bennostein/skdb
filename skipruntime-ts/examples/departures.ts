import type {
  Context,
  EagerCollection,
  Json,
  Resource,
  SkipService,
} from "@skipruntime/core";
import { runService } from "@skipruntime/server";
import {
  PolledExternalService,
  defaultParamEncoder,
} from "@skipruntime/helpers";

const platform: "wasm" | "native" =
  process.env["SKIP_PLATFORM"] == "native" ? "native" : "wasm";

type Departure = {
  year: string;
  origin: string;
  origin_name: string;
  asylum: string;
  asylum_name: string;
  destination: string;
  destination_name: string;
  persons: string;
};

type Result = {
  results: Departure[];
};

type ResourceInputs = { config: EagerCollection<string, (string | number)[]> };

class DeparturesResource implements Resource<ResourceInputs> {
  instantiate(
    cs: ResourceInputs,
    context: Context,
  ): EagerCollection<number, Departure> {
    const get = (name: string, def: string) => {
      try {
        return cs.config.getUnique(name).join(",");
      } catch (_e) {
        return def;
      }
    };
    const params = {
      page: 1,
      year: get("year", "2016,2017"),
      origin: get("origin", "MMR,SYR"),
      asylum: get("asylum", "JOR,LBN"),
      resettlement: get("resettlement", "NOR,USA"),
    };

    return context.useExternalResource({
      service: "polled",
      identifier: "unhcrDeparturesAPI",
      params,
    });
  }
}

const service: SkipService<ResourceInputs, ResourceInputs> = {
  initialData: { config: [] },
  resources: {
    departures: DeparturesResource,
  },
  externalServices: {
    polled: new PolledExternalService({
      unhcrDeparturesAPI: {
        url: "https://api.unhcr.org/rsq/v1/departures",
        interval: 10000,
        conv: (data: Json) =>
          (data as Result).results.map((v, idx) => [idx, [v]]),
        options: { timeout: 1500 },
      },
    }),
  },
  createGraph: (ic) => ic,
};

const server = await runService(service, {
  control_port: 3591,
  streaming_port: 3590,
  platform,
});

async function shutdown() {
  await server.close();
}

// eslint-disable-next-line @typescript-eslint/no-misused-promises
["SIGTERM", "SIGINT"].map((sig) => process.on(sig, shutdown));

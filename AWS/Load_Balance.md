No step creates a separate load-balancer *application* that carries your traffic. The answer depends on which option you chose in Step 6.

## Option A (Service type LoadBalancer)

Nothing extra runs in your cluster. When you apply `service.yaml`, Kubernetes asks AWS to create an Elastic Load Balancer. That ELB is a managed AWS resource that lives outside your cluster, on AWS-owned infrastructure, so you don't deploy, patch, or scale it.

Traffic flows like this:

```
Client → AWS ELB → worker node (NodePort) → kube-proxy → one of your 3 pods
```

`kube-proxy` is a component that already runs on every node (installed by EKS). It does the second layer of balancing from the node to a pod.

## Option B (ALB with Ingress)

This is the only place where an extra app is installed: **step 3, installing the AWS Load Balancer Controller with Helm**.

- It runs as pods (usually 2 replicas) in the `kube-system` namespace.
- It is a **controller, not a load balancer**. It doesn't handle your traffic.
- It watches for Ingress resources, then calls the AWS API to create the real ALB, listeners, and target groups.
- Steps 1 and 2 (OIDC and IAM role) exist only to give that controller permission to make those AWS calls.

Traffic flows like this:

```
Client → AWS ALB → your pods directly (target-type: ip)
```

The controller isn't in this path. If it crashed, existing traffic would keep working, but new Ingress changes wouldn't be applied.

## Summary

| | Extra app you deploy? | What actually balances traffic |
|---|---|---|
| Option A | None | AWS ELB (managed) + kube-proxy |
| Option B | Yes, the LB Controller (management only) | AWS ALB (managed) |

If you want the quickest path to see load balancing work, Option A is enough, with nothing extra to install.
